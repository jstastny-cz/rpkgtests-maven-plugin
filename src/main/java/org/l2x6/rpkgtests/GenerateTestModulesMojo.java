/**
 * Copyright (c) 2019 Repackage Tests Maven Plugin
 * project contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.l2x6.rpkgtests;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.FileUtils;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

/**
 * Generates modules necessary to run repackaged tests.
 *
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 *
 * @since 0.4.0
 */
@Mojo(name = "create-test-modules", requiresDependencyResolution = ResolutionScope.NONE, defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class GenerateTestModulesMojo extends AbstractTestJarsConsumerMojo {
    static final String DEFAULT_TEMPLATES_URI_BASE = "classpath:/create-test-modules-templates";
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";
    private static final Pattern INDENT_PATTERN = Pattern.compile("<project[^>]*>[\r\n]*([ \t]*)<");
    private static final String MANAGED_MODULES_START = "<!-- START: modules generated by rpkgtests-maven-plugin -->";
    private static final String MANAGED_MODULES_END = "<!-- END: modules generated by rpkgtests-maven-plugin -->";

    /**
     * The directory under which the test modules will be generated
     *
     * @since 0.4.0
     */
    @Parameter(property = "rpkgtests.testModulesParentDir", defaultValue = "${project.basedir}")
    private Path testModulesParentDir;

    /**
     * URI prefix to use when looking up FreeMarker templates when generating various source files. You need to touch
     * this only if you want to provide your own custom templates.
     * <p>
     * The following URI schemes are supported:
     * <ul>
     * <li>{@code classpath:}</li>
     * <li>{@code file:} (relative to {@link #basedir})</li>
     * </ul>
     * These are the template files you may want to provide under your custom {@link #templatesUriBase}:
     * <ul>
     * <li>{@code rpkg-module-pom.xml}</li>
     * <li>{@code run-tests-module-pom.xml}</li>
     * </ul>
     * Note that you do not need to provide all of them. Files not available in your custom {@link #templatesUriBase}
     * will be looked up in the default URI base {@value #DEFAULT_TEMPLATES_URI_BASE}. The default templates are
     * maintained <a href=
     * "https://github.com/ppalaga/rpkgtests-maven-plugin/tree/master/src/main/resources/create-test-modules-templates">here</a>.
     *
     * @since 0.4.0
     */
    @Parameter(defaultValue = DEFAULT_TEMPLATES_URI_BASE, required = true, property = "rpkgtests.templatesUriBase")
    private String templatesUriBase;

    /**
     * A comma or whitespace separated list of {@code /pattern/replacement/} replacers that will be sequentially applied
     * to artifactIds coming from {@link #testJars} and {@link #testJarFiles} to produce the atifactIds of the generared
     * test modules.
     *
     * @since 0.4.0
     */
    @Parameter(property = "rpkgtests.testModuleArtifactIdReplacers")
    private String testModuleArtifactIdReplacers;

    /**
     * A comma or whitespace separated list of {@code /pattern/replacement/} replacers that will be sequentially applied
     * to artifactIds coming from {@link #testJars} and {@link #testJarFiles} to produce the directory names for the
     * generared test modules.
     *
     * @since 0.4.0
     */
    @Parameter(property = "rpkgtests.testModuleDirReplacers")
    private String testModuleDirReplacers;

    /**
     * If {@code true} all subdirectories of {@link #testModulesParentDir} containing a {@code pom.xml} file will be
     * deleted before generating all the modules anew; otherwise the modules will be re-generated one by one possibly
     * overwriting the existing files.
     *
     * @since 0.4.0
     */
    @Parameter(property = "rpkgtests.clean", defaultValue = "true")
    private boolean clean;

    /**
     * The artifactId of the module producing the {@code -rpkg} artifacts.
     *
     * @since 0.4.0
     */
    @Parameter(property = "rpkgtests.rpkgModulePomXmlPath")
    private Path rpkgModulePomXmlPath;

    /**
     * The version of the {@code rpkgtests-maven-plugin} to use in the generated {@link #rpkgModulePomXmlPath}.
     *
     * @since 0.4.0
     */
    @Parameter(property = "rpkgtests.rpkgtestsPluginVersion")
    private String rpkgtestsPluginVersion;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Set<TestJar> testJars = getTestJarsOrFail();

        final Path testsParentPath = testModulesParentDir.resolve("pom.xml");
        final TestJar parentPom = TestJar.read(testsParentPath, getCharset());
        final Replacers artifactIdReplacers = Replacers.parse(testModuleArtifactIdReplacers);
        final Replacers dirReplacers = Replacers.parse(testModuleDirReplacers);
        final TestJar rpkgPom = TestJar.read(rpkgModulePomXmlPath, getCharset());

        if (clean) {
            try (Stream<Path> files = Files.list(testModulesParentDir)) {
                files
                        .filter(Files::isDirectory)
                        .filter(p -> Files.isRegularFile(p.resolve("pom.xml")))
                        .forEach(p -> {
                            try {
                                FileUtils.deleteDirectory(p.toFile());
                            } catch (IOException e) {
                                throw new RuntimeException("Could not delete " + p, e);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Could not walk " + testModulesParentDir, e);
            }
        }
        final Configuration cfg = new Configuration(Configuration.VERSION_2_3_28);
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setTemplateLoader(createTemplateLoader(baseDir, templatesUriBase));
        cfg.setDefaultEncoding(getCharset().name());
        cfg.setInterpolationSyntax(Configuration.SQUARE_BRACKET_INTERPOLATION_SYNTAX);
        cfg.setTagSyntax(Configuration.SQUARE_BRACKET_TAG_SYNTAX);

        final List<String> modules = new ArrayList<String>();
        for (TestJar testJar : testJars) {
            final String artifactId = artifactIdReplacers.apply(testJar.getArtifactId());
            final String dir = dirReplacers.apply(testJar.getArtifactId());
            final Path moduleDir = testModulesParentDir.resolve(dir);
            modules.add(dir);
            try {
                Files.createDirectories(moduleDir);
            } catch (IOException e) {
                throw new MojoExecutionException("Could not create " + moduleDir, e);
            }
            final Path pomXmlPath = moduleDir.resolve("pom.xml");
            final TestJar runTestsModule = parentPom.withArtifactId(artifactId);

            final TemplateParams model = new TemplateParams(parentPom, "../pom.xml", runTestsModule, rpkgPom, testJar, testJars,
                    rpkgtestsPluginVersion);
            try {
                evalTemplate(cfg, "run-tests-module-pom.xml", pomXmlPath, getCharset(), model);
            } catch (IOException | TemplateException e) {
                throw new RuntimeException(e);
            }
        }

        final TemplateParams model = new TemplateParams(parentPom, "../pom.xml", null, rpkgPom, null, testJars, rpkgtestsPluginVersion);
        try {
            evalTemplate(cfg, "rpkg-module-pom.xml", rpkgModulePomXmlPath, getCharset(), model);
        } catch (IOException | TemplateException e) {
            throw new RuntimeException(e);
        }

        try {
            String testsParentSource = new String(Files.readAllBytes(testsParentPath), getCharset());
            testsParentSource = addModules(testsParentSource, testsParentPath, modules);
            Files.write(testsParentPath, testsParentSource.getBytes(getCharset()));
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + testsParentPath, e);
        }
    }

    static String addModules(String testsParentSource, Path path, List<String> modules) {
        final StringBuilder result = new StringBuilder(testsParentSource);
        final String eol = result.indexOf("\r") >= 0 ? "\r\n" : "\n";
        final Matcher m = INDENT_PATTERN.matcher(result);
        final String indent = m.find() ? m.group(1) : "    ";

        int insertPos = result.indexOf(MANAGED_MODULES_START);
        if (insertPos < 0) {
            insertPos = result.indexOf("<modules>");
            if (insertPos < 0) {
                final int endProjectPos =  result.lastIndexOf("</project>");
                if (endProjectPos >= 0)  {
                    result.insert(endProjectPos, indent + "<modules>" + eol + indent + indent + MANAGED_MODULES_START + eol
                            + indent + indent + MANAGED_MODULES_END + eol + indent + "</modules>" + eol);
                    insertPos = result.indexOf(MANAGED_MODULES_START);
                } else {
                    throw new IllegalStateException("Could not find </project> in "+ path);
                }
            } else {
                insertPos += "<modules>".length();
                insertPos = consumeEol(result, insertPos);
                result.insert(insertPos, indent + indent + MANAGED_MODULES_START + eol + indent + indent + MANAGED_MODULES_END + eol);
                insertPos = result.indexOf(MANAGED_MODULES_START);
            }
        }
        insertPos += MANAGED_MODULES_START.length();
        final int delPos = result.indexOf(MANAGED_MODULES_END);
        result.replace(insertPos, delPos, eol + indent + indent);

        for (int i = modules.size() - 1; i >= 0; i--) {
            final String module = modules.get(i);
            result.insert(insertPos, eol + indent + indent + "<module>" + module + "</module>");
        }

        return result.toString();
    }

    private static int consumeEol(StringBuilder result, int insertPos) {
        while (insertPos < result.length()) {
            switch (result.charAt(insertPos)) {
            case '\r':
            case '\n':
                insertPos++;
                break;
            default:
                return insertPos;
            }
        }
        return insertPos;
    }

    static TemplateLoader createTemplateLoader(Path basedir, String templatesUriBase) {
        final TemplateLoader defaultLoader = new ClassTemplateLoader(GenerateTestModulesMojo.class,
                DEFAULT_TEMPLATES_URI_BASE.substring(CLASSPATH_PREFIX.length()));
        if (DEFAULT_TEMPLATES_URI_BASE.equals(templatesUriBase)) {
            return defaultLoader;
        } else if (templatesUriBase.startsWith(CLASSPATH_PREFIX)) {
            return new MultiTemplateLoader( //
                    new TemplateLoader[] { //
                            new ClassTemplateLoader(GenerateTestModulesMojo.class,
                                    templatesUriBase.substring(CLASSPATH_PREFIX.length())), //
                            defaultLoader //
                    });
        } else if (templatesUriBase.startsWith(FILE_PREFIX)) {
            try {
                return new MultiTemplateLoader( //
                        new TemplateLoader[] { //
                                new FileTemplateLoader(
                                        basedir.resolve(templatesUriBase.substring(FILE_PREFIX.length())).toFile()), //
                                defaultLoader //
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new IllegalStateException(String.format(
                    "Cannot handle templatesUriBase '%s'; only value starting with '%s' or '%s' are supported",
                    templatesUriBase, CLASSPATH_PREFIX, FILE_PREFIX));
        }
    }

    public void setTestModulesParentDir(File testModulesParentDir) {
        this.testModulesParentDir = testModulesParentDir.toPath();
    }

    public void setRpkgModulePomXmlPath(File rpkgModulePomXmlPath) {
        this.rpkgModulePomXmlPath = rpkgModulePomXmlPath.toPath();
    }

    static void evalTemplate(Configuration cfg, String templateUri, Path dest, Charset charset, TemplateParams model)
            throws IOException, TemplateException {
        final Template template = cfg.getTemplate(templateUri);
        Files.createDirectories(dest.getParent());
        try (Writer out = Files.newBufferedWriter(dest)) {
            template.process(model, out);
        }
    }

    public static class TemplateParams {
        final TestJar parent;
        final String parentRelativePath;
        final TestJar runTestsModule;
        final TestJar rpkgModule;
        final TestJar testJar;
        final Set<TestJar> testJars;
        final String rpkgtestsPluginVersion;

        public TemplateParams(TestJar parent, String parentRelativePath, TestJar runTestsModule,
                TestJar rpkgModule, TestJar testJar, Set<TestJar> testJars, String rpkgtestsPluginVersion) {
            this.parent = parent;
            this.parentRelativePath = parentRelativePath;
            this.runTestsModule = runTestsModule;
            this.rpkgModule = rpkgModule;
            this.testJar= testJar;
            this.testJars = testJars;
            this.rpkgtestsPluginVersion = rpkgtestsPluginVersion;
        }

        public TestJar getParent() {
            return parent;
        }

        public String getParentRelativePath() {
            return parentRelativePath;
        }

        public TestJar getRunTestsModule() {
            return runTestsModule;
        }

        public TestJar getRpkgModule() {
            return rpkgModule;
        }

        public TestJar getTestJar() {
            return testJar;
        }

        public Set<TestJar> getTestJars() {
            return testJars;
        }

        public String getRpkgtestsPluginVersion() {
            return rpkgtestsPluginVersion;
        }

    }

    static class Replacers {
        public static Replacers parse(String replacers) {
            if (replacers == null) {
                return new Replacers(Collections.emptyList());
            }
            return new Replacers(Arrays.stream(replacers.split(",\\s"))
                    .map(Replacer::parse)
                    .collect(Collectors.toList()));
        }

        private final List<Replacer> replacers;

        Replacers(List<Replacer> replacers) {
            this.replacers = replacers;
        }

        public String apply(String input) {
            for (Replacer replacer : replacers) {
                input = replacer.apply(input);
            }
            return input;
        }

        static class Replacer {


            public static Replacer parse(String rawReplacer) {
                if (!rawReplacer.startsWith("/")) {
                    throw new IllegalArgumentException("Replacer must start with a slash; found "+ rawReplacer);
                }
                if (!rawReplacer.endsWith("/")) {
                    throw new IllegalArgumentException("Replacer must end with a slash; found "+ rawReplacer);
                }
                final String trimmed = rawReplacer.substring(1, rawReplacer.length() - 1);
                final int slashPos = trimmed.indexOf('/');
                if (slashPos < 0) {
                    throw new IllegalArgumentException("Replacer must contain three slashes; found " + rawReplacer);
                }
                return new Replacer(Pattern.compile(trimmed.substring(0, slashPos)), trimmed.substring(slashPos + 1));
            }

            private final Pattern pattern;
            private final String replacement;

            Replacer(Pattern pattern, String replacement) {
                this.pattern = pattern;
                this.replacement = replacement;
            }

            public String apply(String artifactId) {
                return pattern.matcher(artifactId).replaceAll(replacement);
            }
        }
    }
}
