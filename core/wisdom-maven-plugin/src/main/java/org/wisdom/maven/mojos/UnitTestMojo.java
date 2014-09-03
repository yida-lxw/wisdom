/*
 * #%L
 * Wisdom-Framework
 * %%
 * Copyright (C) 2013 - 2014 Wisdom Framework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.wisdom.maven.mojos;

import com.google.common.collect.ImmutableList;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.surefire.report.ReportTestCase;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.wisdom.maven.WatchingException;
import org.wisdom.maven.utils.PluginExtractor;
import org.wisdom.maven.utils.WatcherUtils;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * A Mojo executing Surefire (unit test execution) and bridging it with the Wisdom's watch mode.
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.TEST, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.TEST)
public class UnitTestMojo extends AbstractWisdomWatcherMojo {

    /**
     * The surefire maven plugin name.
     */
    public static final String MAVEN_SUREFIRE_PLUGIN = "maven-surefire-plugin";

    /**
     * The default version of Surefire
     */
    public static final String DEFAULT_VERSION = "2.17";

    /**
     * The groupId of the Surefire maven plugin.
     */
    public static final String GROUP_ID = "org.apache.maven.plugins";

    /**
     * The goal used to execute the tests.
     */
    public static final String COMPILE_GOAL = "test";

    /**
     * If set to {@literal true}, skip the test execution.
     */
    @Parameter(property = "skipTests", defaultValue = "false")
    protected boolean skipTests;

    /**
     * Sets the test selection strategy in watch mode. By default, all test are executed. The {@literal SELECTIVE}
     * policy executes only the test related to the current change.
     */
    @Parameter(defaultValue = "ALL")
    protected TestSelectionPolicy testSelectionPolicy;

    /**
     * The directory where Surefire execution reports are stored.
     */
    private File reports;

    /**
     * Executes the unit tests
     *
     * @throws MojoExecutionException the tests failed.
     */
    public void execute() throws MojoExecutionException {
        reports = new File(basedir, "target/surefire-reports");
        if (skipTests) {
            getLog().info("Test skipped");
            removeFromWatching();
            return;
        }
        execute(null);
    }

    /**
     * Executes the unit tests.
     *
     * @param testParameter an optional parameter to select the test to execute. this parameter is used in the
     *                      {@literal SELECTIVE} policy.
     * @throws MojoExecutionException the tests failed
     */
    public void execute(String testParameter) throws MojoExecutionException {
        String version = PluginExtractor.getBuildPluginVersion(this, MAVEN_SUREFIRE_PLUGIN);
        Xpp3Dom configuration = PluginExtractor.getBuildPluginMainConfiguration(this, MAVEN_SUREFIRE_PLUGIN);

        if (version == null) {
            version = DEFAULT_VERSION;
        }

        if (configuration == null) {
            configuration = configuration(
                    element(name("testSourceDirectory"), "${project.build.testSourceDirectory}")
            );
        } else {
            getLog().debug("Loading maven-surefire-plugin configuration:");
            getLog().debug(configuration.toString());
        }

        // Surefire detects the already executed configurations, to avoid re-executing tests.
        // Obviously in our case, this is annoying. So we inject a system property to hack the Sha1 computation used
        // to detect already executed configurations.
        Xpp3Dom sys = configuration.getChild("systemPropertyVariables");
        if (sys == null) {
            sys = new Xpp3Dom("systemPropertyVariables");
            configuration.addChild(sys);
        }

        Xpp3Dom hashHack = new Xpp3Dom("__surefire_hash_hack__");
        hashHack.setValue(String.valueOf(System.currentTimeMillis()));
        sys.addChild(hashHack);

        // The test parameter argument is used to select the test to execute. We inject this parameter here:
        if (testParameter != null) {
            Xpp3Dom test = new Xpp3Dom("test");
            test.setValue(testParameter);
            configuration.addChild(test);

            // Also disables the 'fail if no test'
            Xpp3Dom failIfNotTest = configuration.getChild("failIfNoTests");
            if (failIfNotTest == null) {
                failIfNotTest = new Xpp3Dom("failIfNoTests");
                configuration.addChild(failIfNotTest);
            }
            failIfNotTest.setValue("false");

            // To get the right summary, we need to delete existing surefire-report
            FileUtils.deleteQuietly(reports);
        }

        // Execute the tests.
        executeMojo(
                plugin(
                        groupId(GROUP_ID),
                        artifactId(MAVEN_SUREFIRE_PLUGIN),
                        version(version)
                ),
                goal(COMPILE_GOAL),
                configuration,
                executionEnvironment(
                        project,
                        session,
                        pluginManager
                )
        );
    }

    /**
     * Do we have to execute some tests when the given file is changed.
     *
     * @param file is the file.
     * @return {@literal true} if the file is a java file from either {@literal src/main/java} or from {@literal
     * src/test/java}, {@literal false} otherwise.
     */
    @Override
    public boolean accept(File file) {
        return
                WatcherUtils.isInDirectory(file, WatcherUtils.getJavaSource(basedir))
                        || WatcherUtils.isInDirectory(file, new File(basedir, "src/test/java"));
    }

    /**
     * Notifies the watcher that a new file is created. It selects and executes the test. Failures and errors are
     * reported in the thrown {@link org.wisdom.maven.WatchingException}.
     *
     * @param file is the file.
     * @return return {@code true}
     * @throws org.wisdom.maven.WatchingException if the test execution failed.
     */
    @Override
    public boolean fileCreated(File file) throws WatchingException {
        // Check selection policy
        String testParameter = null;
        if (testSelectionPolicy == TestSelectionPolicy.SELECTIVE) {
            // The test selection is done using the -Dtest parameter from surefire
            testParameter = "*" + file.getName().substring(0, file.getName().lastIndexOf(".")) + "*";
        }

        try {
            execute(testParameter);
            return true;
        } catch (MojoExecutionException e) {
            // Compute the Watching Exception content.
            String message = "";
            SurefireReportParser parser = new SurefireReportParser(ImmutableList.of(reports), Locale.ENGLISH);
            try {
                List<ReportTestSuite> suites = parser.parseXMLReportFiles();
                Map<String, String> summary = parser.getSummary(suites);
                message += summary.get("totalTests") + " tests, " + summary.get("totalErrors") + " errors, " +
                        "" + summary.get("totalFailures") + " failures, " + summary.get("totalSkipped") + " skipped " +
                        "(executed in " + summary.get("totalElapsedTime") + "s)<br/><ul>";
                for (ReportTestSuite suite : suites) {
                    if (suite.getNumberOfErrors() > 0 || suite.getNumberOfFailures() > 0) {
                        for (ReportTestCase tc : suite.getTestCases()) {
                            if (tc.getFailure() != null
                                    && !"skipped".equalsIgnoreCase((String) tc.getFailure().get("message"))) {
                                message += "<li><em>" + tc.getFullName() + "</em> failed: " + tc.getFailure().get
                                        ("message") + "</li>";
                            }
                        }
                    }
                }
                message += "</ul>";
                throw new WatchingException("Unit Test Failure", message, file, e);
            } catch (MavenReportException reportException) {
                // Cannot read the reports.
                throw new WatchingException("Unit Test Failure", file, reportException);
            }
        }
    }

    /**
     * Notifies the watcher that a file has been modified. Just calls {@link #fileCreated(java.io.File)}
     *
     * @param file is the file.
     * @return {@literal false} if the pipeline processing must be interrupted for this event. Most watchers should
     * returns {@literal true} to let other watchers to be notified.
     * @throws org.wisdom.maven.WatchingException if the watcher failed to process the given file.
     */
    @Override
    public boolean fileUpdated(File file) throws WatchingException {
        return fileCreated(file);
    }

    /**
     * Notifies the watcher that a file was deleted. Just calls {@link #fileCreated(java.io.File)}
     *
     * @param file the file
     * @return {@literal false} if the pipeline processing must be interrupted for this event. Most watchers should
     * return {@literal true} to let other watchers be notified.
     * @throws org.wisdom.maven.WatchingException if the watcher failed to process the given file.
     */
    @Override
    public boolean fileDeleted(File file) throws WatchingException {
        return fileCreated(file);
    }
}
