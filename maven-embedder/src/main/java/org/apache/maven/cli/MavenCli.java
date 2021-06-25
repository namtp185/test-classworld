package org.apache.maven.cli;

import static org.apache.maven.cli.ResolveFile.resolveFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.maven.BuildAbort;
import org.apache.maven.cli.internal.DefaultService2;
import org.apache.maven.cli.logging.Slf4jConfiguration;
import org.apache.maven.cli.logging.Slf4jConfigurationFactory;
import org.apache.maven.cli.logging.Slf4jLoggerManager;
import org.apache.maven.cli.logging.Slf4jStdoutLogger;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.apache.maven.properties.internal.SystemProperties;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.classworlds.realm.NoSuchRealmException;
import org.codehaus.plexus.logging.LoggerManager;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.google.inject.AbstractModule;

// TODO push all common bits back to plexus cli and prepare for transition to Guice. We don't need 50 ways to make CLIs

/**
 * @author Jason van Zyl
 */
public class MavenCli {
	public static final String LOCAL_REPO_PROPERTY = "maven.repo.local";

	public static final String MULTIMODULE_PROJECT_DIRECTORY = "maven.multiModuleProjectDirectory";

	public static final String USER_HOME = System.getProperty("user.home");

	public static final File USER_MAVEN_CONFIGURATION_HOME = new File(USER_HOME, ".m2");

	public static final File DEFAULT_USER_TOOLCHAINS_FILE = new File(USER_MAVEN_CONFIGURATION_HOME, "toolchains.xml");

	public static final File DEFAULT_GLOBAL_TOOLCHAINS_FILE = new File(System.getProperty("maven.conf"),
			"toolchains.xml");

	private static final String MVN_MAVEN_CONFIG = ".mvn/maven.config";

	public static final String STYLE_COLOR_PROPERTY = "style.color";

	private ClassWorld classWorld;

	private LoggerManager plexusLoggerManager;

	private ILoggerFactory slf4jLoggerFactory;

	private Logger slf4jLogger;

	public MavenCli() {
		this(null);
	}

	// This supports painless invocation by the Verifier during embedded execution
	// of the core ITs
	public MavenCli(ClassWorld classWorld) {
		this.classWorld = classWorld;
	}

	public static void main(String[] args) {
		int result = main(args, null);

		System.exit(result);
	}

	public static int main(String[] args, ClassWorld classWorld) {
		MavenCli cli = new MavenCli();

		MessageUtils.systemInstall();
		MessageUtils.registerShutdownHook();
		int result = cli.doMain(new CliRequest(args, classWorld));
		MessageUtils.systemUninstall();

		return result;
	}

	// TODO need to externalize CliRequest
	public static int doMain(String[] args, ClassWorld classWorld) {
		MavenCli cli = new MavenCli();
		return cli.doMain(new CliRequest(args, classWorld));
	}

	/**
	 * This supports painless invocation by the Verifier during embedded execution
	 * of the core ITs. See <a href=
	 * "http://maven.apache.org/shared/maven-verifier/xref/org/apache/maven/it/Embedded3xLauncher.html">
	 * <code>Embedded3xLauncher</code> in <code>maven-verifier</code></a>
	 */
	public int doMain(String[] args, String workingDirectory, PrintStream stdout, PrintStream stderr) {
		PrintStream oldout = System.out;
		PrintStream olderr = System.err;

		final Set<String> realms;
		if (classWorld != null) {
			realms = new HashSet<>();
			for (ClassRealm realm : classWorld.getRealms()) {
				realms.add(realm.getId());
			}
		} else {
			realms = Collections.emptySet();
		}

		try {
			if (stdout != null) {
				System.setOut(stdout);
			}
			if (stderr != null) {
				System.setErr(stderr);
			}

			CliRequest cliRequest = new CliRequest(args, classWorld);
			cliRequest.workingDirectory = workingDirectory;

			return doMain(cliRequest);
		} finally {
			if (classWorld != null) {
				for (ClassRealm realm : new ArrayList<>(classWorld.getRealms())) {
					String realmId = realm.getId();
					if (!realms.contains(realmId)) {
						try {
							classWorld.disposeRealm(realmId);
						} catch (NoSuchRealmException ignored) {
							// can't happen
						}
					}
				}
			}
			System.setOut(oldout);
			System.setErr(olderr);
		}
	}

	// TODO need to externalize CliRequest
	public int doMain(CliRequest cliRequest) {
		PlexusContainer localContainer = null;
		try {
			initialize(cliRequest);
			cli(cliRequest);
			properties(cliRequest);
			logging(cliRequest);
			version(cliRequest);
			localContainer = container(cliRequest);
			return 0;
		} catch (ExitException e) {
			return e.exitCode;
		} catch (UnrecognizedOptionException e) {
			// pure user error, suppress stack trace
			return 1;
		} catch (BuildAbort e) {
			CLIReportingUtils.showError(slf4jLogger, "ABORTED", e, cliRequest.showErrors);

			return 2;
		} catch (Exception e) {
			CLIReportingUtils.showError(slf4jLogger, "Error executing Maven.", e, cliRequest.showErrors);

			return 1;
		} finally {
			if (localContainer != null) {
				localContainer.dispose();
			}
		}
	}

	void initialize(CliRequest cliRequest) throws ExitException {
		if (cliRequest.workingDirectory == null) {
			cliRequest.workingDirectory = System.getProperty("user.dir");
		}

		if (cliRequest.multiModuleProjectDirectory == null) {
			String basedirProperty = System.getProperty(MULTIMODULE_PROJECT_DIRECTORY);
			if (basedirProperty == null) {
				System.err.format("-D%s system property is not set.", MULTIMODULE_PROJECT_DIRECTORY);
				throw new ExitException(1);
			}
			File basedir = basedirProperty != null ? new File(basedirProperty) : new File("");
			try {
				cliRequest.multiModuleProjectDirectory = basedir.getCanonicalFile();
			} catch (IOException e) {
				cliRequest.multiModuleProjectDirectory = basedir.getAbsoluteFile();
			}
		}

		//
		// Make sure the Maven home directory is an absolute path to save us from
		// confusion with say drive-relative
		// Windows paths.
		//
		String mavenHome = System.getProperty("maven.home");

		if (mavenHome != null) {
			System.setProperty("maven.home", new File(mavenHome).getAbsolutePath());
		}
	}

	void cli(CliRequest cliRequest) throws Exception {
		//
		// Parsing errors can happen during the processing of the arguments and we
		// prefer not having to check if
		// the logger is null and construct this so we can use an SLF4J logger
		// everywhere.
		//
		slf4jLogger = new Slf4jStdoutLogger();

		CLIManager cliManager = new CLIManager();

		List<String> args = new ArrayList<>();
		CommandLine mavenConfig = null;
		try {
			File configFile = new File(cliRequest.multiModuleProjectDirectory, MVN_MAVEN_CONFIG);

			if (configFile.isFile()) {
				for (String arg : new String(Files.readAllBytes(configFile.toPath())).split("\\s+")) {
					if (!arg.isEmpty()) {
						args.add(arg);
					}
				}

				mavenConfig = cliManager.parse(args.toArray(new String[0]));
				List<?> unrecongized = mavenConfig.getArgList();
				if (!unrecongized.isEmpty()) {
					throw new ParseException("Unrecognized maven.config entries: " + unrecongized);
				}
			}
		} catch (ParseException e) {
			System.err.println("Unable to parse maven.config: " + e.getMessage());
			cliManager.displayHelp(System.out);
			throw e;
		}

		try {
			if (mavenConfig == null) {
				cliRequest.commandLine = cliManager.parse(cliRequest.args);
			} else {
				cliRequest.commandLine = cliMerge(cliManager.parse(cliRequest.args), mavenConfig);
			}
		} catch (ParseException e) {
			System.err.println("Unable to parse command line options: " + e.getMessage());
			cliManager.displayHelp(System.out);
			throw e;
		}

		if (cliRequest.commandLine.hasOption(CLIManager.HELP)) {
			cliManager.displayHelp(System.out);
			throw new ExitException(0);
		}

		if (cliRequest.commandLine.hasOption(CLIManager.VERSION)) {
			System.out.println(CLIReportingUtils.showVersion());
			throw new ExitException(0);
		}
	}

	private CommandLine cliMerge(CommandLine mavenArgs, CommandLine mavenConfig) {
		CommandLine.Builder commandLineBuilder = new CommandLine.Builder();

		// the args are easy, cli first then config file
		for (String arg : mavenArgs.getArgs()) {
			commandLineBuilder.addArg(arg);
		}
		for (String arg : mavenConfig.getArgs()) {
			commandLineBuilder.addArg(arg);
		}

		// now add all options, except for -D with cli first then config file
		List<Option> setPropertyOptions = new ArrayList<>();
		for (Option opt : mavenArgs.getOptions()) {
			if (String.valueOf(CLIManager.SET_SYSTEM_PROPERTY).equals(opt.getOpt())) {
				setPropertyOptions.add(opt);
			} else {
				commandLineBuilder.addOption(opt);
			}
		}
		for (Option opt : mavenConfig.getOptions()) {
			commandLineBuilder.addOption(opt);
		}
		// finally add the CLI system properties
		for (Option opt : setPropertyOptions) {
			commandLineBuilder.addOption(opt);
		}
		return commandLineBuilder.build();
	}

	/**
	 * configure logging
	 */
	void logging(CliRequest cliRequest) {
		// LOG LEVEL
		cliRequest.debug = cliRequest.commandLine.hasOption(CLIManager.DEBUG);
		cliRequest.quiet = !cliRequest.debug && cliRequest.commandLine.hasOption(CLIManager.QUIET);
		cliRequest.showErrors = cliRequest.debug || cliRequest.commandLine.hasOption(CLIManager.ERRORS);

		slf4jLoggerFactory = LoggerFactory.getILoggerFactory();
		Slf4jConfiguration slf4jConfiguration = Slf4jConfigurationFactory.getConfiguration(slf4jLoggerFactory);

		// else fall back to default log level specified in conf
		// see https://issues.apache.org/jira/browse/MNG-2570

		// LOG COLOR
		String styleColor = cliRequest.getUserProperties().getProperty(STYLE_COLOR_PROPERTY, "auto");
		if ("always".equals(styleColor)) {
			MessageUtils.setColorEnabled(true);
		} else if ("never".equals(styleColor)) {
			MessageUtils.setColorEnabled(false);
		} else if (!"auto".equals(styleColor)) {
			throw new IllegalArgumentException("Invalid color configuration option [" + styleColor
					+ "]. Supported values are (auto|always|never).");
		} else if (cliRequest.commandLine.hasOption(CLIManager.BATCH_MODE)
				|| cliRequest.commandLine.hasOption(CLIManager.LOG_FILE)) {
			MessageUtils.setColorEnabled(false);
		}

		// LOG STREAMS
		if (cliRequest.commandLine.hasOption(CLIManager.LOG_FILE)) {
			File logFile = new File(cliRequest.commandLine.getOptionValue(CLIManager.LOG_FILE));
			logFile = resolveFile(logFile, cliRequest.workingDirectory);

			// redirect stdout and stderr to file
			try {
				PrintStream ps = new PrintStream(new FileOutputStream(logFile));
				System.setOut(ps);
				System.setErr(ps);
			} catch (FileNotFoundException e) {
				//
				// Ignore
				//
			}
		}

		slf4jConfiguration.activate();

		plexusLoggerManager = new Slf4jLoggerManager();
		slf4jLogger = slf4jLoggerFactory.getLogger(this.getClass().getName());
	}

	private void version(CliRequest cliRequest) {
		if (cliRequest.debug || cliRequest.commandLine.hasOption(CLIManager.SHOW_VERSION)) {
			System.out.println(CLIReportingUtils.showVersion());
		}
	}

	// Needed to make this method package visible to make writing a unit test
	// possible
	// Maybe it's better to move some of those methods to separate class (SoC).
	void properties(CliRequest cliRequest) {
		populateProperties(cliRequest.commandLine, cliRequest.systemProperties, cliRequest.userProperties);
	}

	PlexusContainer container(CliRequest cliRequest) throws Exception {
		if (cliRequest.classWorld == null) {
			cliRequest.classWorld = new ClassWorld("plexus.core", Thread.currentThread().getContextClassLoader());
		}

		ClassRealm coreRealm = cliRequest.classWorld.getClassRealm("plexus.core");
		if (coreRealm == null) {
			coreRealm = cliRequest.classWorld.getRealms().iterator().next();
		}

		coreRealm.display();

		ContainerConfiguration cc = new DefaultContainerConfiguration().setClassWorld(cliRequest.classWorld)
				.setRealm(coreRealm).setClassPathScanning(PlexusConstants.SCANNING_INDEX).setAutoWiring(true)
				.setJSR250Lifecycle(true).setName("maven");

		DefaultPlexusContainer container = new DefaultPlexusContainer(cc, new AbstractModule() {
			@Override
			protected void configure() {
				bind(ILoggerFactory.class).toInstance(slf4jLoggerFactory);
			}
		});

		// NOTE: To avoid inconsistencies, we'll use the TCCL exclusively for lookups
		container.setLookupRealm(null);
		Thread.currentThread().setContextClassLoader(container.getContainerRealm());

		container.setLoggerManager(plexusLoggerManager);

		customizeContainer(container);

		// refresh logger in case container got customized by spy
		slf4jLogger = slf4jLoggerFactory.getLogger(this.getClass().getName());

		DefaultService2 resolver = container.lookup(DefaultService2.class);
		resolver.doThing();
		System.out.println(resolver);

		return container;
	}

	void toolchains(CliRequest cliRequest) throws Exception {
		File userToolchainsFile;

		if (cliRequest.commandLine.hasOption(CLIManager.ALTERNATE_USER_TOOLCHAINS)) {
			userToolchainsFile = new File(cliRequest.commandLine.getOptionValue(CLIManager.ALTERNATE_USER_TOOLCHAINS));
			userToolchainsFile = resolveFile(userToolchainsFile, cliRequest.workingDirectory);

			if (!userToolchainsFile.isFile()) {
				throw new FileNotFoundException(
						"The specified user toolchains file does not exist: " + userToolchainsFile);
			}
		} else {
			userToolchainsFile = DEFAULT_USER_TOOLCHAINS_FILE;
		}

		File globalToolchainsFile;

		if (cliRequest.commandLine.hasOption(CLIManager.ALTERNATE_GLOBAL_TOOLCHAINS)) {
			globalToolchainsFile = new File(
					cliRequest.commandLine.getOptionValue(CLIManager.ALTERNATE_GLOBAL_TOOLCHAINS));
			globalToolchainsFile = resolveFile(globalToolchainsFile, cliRequest.workingDirectory);

			if (!globalToolchainsFile.isFile()) {
				throw new FileNotFoundException(
						"The specified global toolchains file does not exist: " + globalToolchainsFile);
			}
		} else {
			globalToolchainsFile = DEFAULT_GLOBAL_TOOLCHAINS_FILE;
		}

//		cliRequest.request.setGlobalToolchainsFile(globalToolchainsFile);
//		cliRequest.request.setUserToolchainsFile(userToolchainsFile);

	}

	int calculateDegreeOfConcurrencyWithCoreMultiplier(String threadConfiguration) {
		int procs = Runtime.getRuntime().availableProcessors();
		return (int) (Float.valueOf(threadConfiguration.replace("C", "")) * procs);
	}

	// ----------------------------------------------------------------------
	// System properties handling
	// ----------------------------------------------------------------------

	static void populateProperties(CommandLine commandLine, Properties systemProperties, Properties userProperties) {
		EnvironmentUtils.addEnvVars(systemProperties);

		// ----------------------------------------------------------------------
		// Options that are set on the command line become system properties
		// and therefore are set in the session properties. System properties
		// are most dominant.
		// ----------------------------------------------------------------------

		if (commandLine.hasOption(CLIManager.SET_SYSTEM_PROPERTY)) {
			String[] defStrs = commandLine.getOptionValues(CLIManager.SET_SYSTEM_PROPERTY);

			if (defStrs != null) {
				for (String defStr : defStrs) {
					setCliProperty(defStr, userProperties);
				}
			}
		}

		SystemProperties.addSystemProperties(systemProperties);

		// ----------------------------------------------------------------------
		// Properties containing info about the currently running version of Maven
		// These override any corresponding properties set on the command line
		// ----------------------------------------------------------------------

		Properties buildProperties = CLIReportingUtils.getBuildProperties();

		String mavenVersion = buildProperties.getProperty(CLIReportingUtils.BUILD_VERSION_PROPERTY);
		System.out.println(mavenVersion);
		systemProperties.setProperty("maven.version", mavenVersion);

		String mavenBuildVersion = CLIReportingUtils.createMavenVersionString(buildProperties);
		systemProperties.setProperty("maven.build.version", mavenBuildVersion);
	}

	private static void setCliProperty(String property, Properties properties) {
		String name;

		String value;

		int i = property.indexOf('=');

		if (i <= 0) {
			name = property.trim();

			value = "true";
		} else {
			name = property.substring(0, i).trim();

			value = property.substring(i + 1);
		}

		properties.setProperty(name, value);

		// ----------------------------------------------------------------------
		// I'm leaving the setting of system properties here as not to break
		// the SystemPropertyProfileActivator. This won't harm embedding. jvz.
		// ----------------------------------------------------------------------

		System.setProperty(name, value);
	}

	static class ExitException extends Exception {
		int exitCode;

		ExitException(int exitCode) {
			this.exitCode = exitCode;
		}
	}

	//
	// Customizations available via the CLI
	//

	protected void customizeContainer(PlexusContainer container) {
	}

}
