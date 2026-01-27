package org.checkerframework.maven.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.codehaus.plexus.util.cli.Commandline;

@Mojo(
    name = "check",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class CheckerMojo extends AbstractMojo {

  /** The default Checker Framework version to use. */
  private final String DEFAULT_CHECKER_FRAMEWORK_VERSION = "3.53.0";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Component private RepositorySystem repositorySystem;

  @Component private ToolchainManager toolchainManager;

  @Parameter(defaultValue = "${localRepository}", readonly = true)
  private ArtifactRepository localRepository;

  @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = true)
  private List<String> compileSourceRoots;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  /**
   * The Checker Framework version to use. If not specified, will try to detect from project
   * dependencies. If still not found, will use a default version.
   */
  @Parameter(property = "checkerFrameworkVersion")
  private String checkerFrameworkVersion;

  /*
   * PARAMETERS BELOW
   */

  /** The list of checkers for the Checker Framework to run */
  @Parameter(property = "annotationProcessors")
  private List<String> annotationProcessors;

  /** Whether to skip execution */
  @Parameter(property = "skip", defaultValue = "false")
  private boolean skip;

  @Parameter(property = "executable", defaultValue = "java")
  private String executable;

  /**
   * Whether to only process annotations, do not generate class files (to avoid overwriting
   * compilation results)
   */
  @Parameter(property = "procOnly", defaultValue = "true")
  private boolean procOnly;

  /** Whether to fail the build when Checker Framework finds errors */
  @Parameter(property = "failOnError", defaultValue = "true")
  private boolean failOnError;

  /** Whether to exclude test sources from checking */
  @Parameter(property = "excludeTests", defaultValue = "false")
  private boolean excludeTests;

  /** Additional javac arguments to pass to the compiler */
  @Parameter(property = "extraJavacArgs")
  private List<String> extraJavacArgs;

  /**
   * Whether to suppress warnings from Lombok-generated code. If true, automatically adds
   * @SuppressWarnings annotations to delombok output.
   */
  @Parameter(property = "suppressLombokWarnings", defaultValue = "true")
  private boolean suppressLombokWarnings;

  /** The Checker Framework JAR file for the checker artifact */
  private File checkerFrameworkJar;

  /** The Checker Framework JAR file for the checker-qual artifact */
  private File checkerQualJar;

  /** The annotated JDK JAR file (jdk8) for Checker Framework */
  private File annotatedJdkJar;

  /**
   * The Java source major version number that configured in the project (e.g., 8 for Java 1.8, 9
   * for Java 9, 11 for Java 11)
   */
  private int javaSourceVersionNumber;

  /**
   * The actual JVM major version number that is running the Maven build (e.g., 8 for Java 1.8, 9
   * for Java 9, 11 for Java 11)
   */
  private int jvmVersionNumber;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    final Log log = getLog();
    if (skipCheckerFramework()) {
      return;
    }

    final String processor =
        (!annotationProcessors.isEmpty())
            ? StringUtils.join(annotationProcessors.iterator(), ",")
            : null;
    if (annotationProcessors == null || annotationProcessors.isEmpty()) {
      log.warn("Skipping Checker Framework: No checkers configured.");
      return;
    } else {
      log.info("Running processor(s): " + processor);
    }

    // Determine Checker Framework version
    // Priority: 1. Explicitly configured version 2. Version from project
    // dependencies 3. Default
    // version
    if (checkerFrameworkVersion == null || checkerFrameworkVersion.isEmpty()) {
      String versionFromDeps = PathUtils.getCheckerFrameworkVersionFromDependencies(project);
      if (versionFromDeps != null && !versionFromDeps.isEmpty()) {
        checkerFrameworkVersion = versionFromDeps;
      } else {
        checkerFrameworkVersion = DEFAULT_CHECKER_FRAMEWORK_VERSION;
      }
    }

    log.info("Starting Checker Framework analysis with version: " + checkerFrameworkVersion);

    // Handle Lombok integration
    LombokIntegration lombokIntegration =
        new LombokIntegration(project, log, annotationProcessors, suppressLombokWarnings);
    lombokIntegration.handleIntegration();

    final List<String> sources = collectSourceRoots(lombokIntegration);
    if (sources.isEmpty()) {
      log.info("No source files found.");
      return;
    }

    File srcFofn = null;
    File cpFofn = null;
    try {
      // Load Java version information first, as it's needed for determining which artifacts to locate
      loadJavaSourceVersionAndJvmVersion();
      locateArtifacts();

      // Prepare javac command
      Commandline commandline = new Commandline();

      concatJavaPath(commandline);

      // Automatically add security parameters for Java 9+ (saves you from the huge
      // configuration in
      // pom.xml)
      if (isJava9OrLater()) {
        addJava9Args(commandline);
      }

      // Add Error Prone javac on demand if needed
      addErrorProneJavacOnDemand(commandline);

      // Add -cp for checker framework jars (needed for java command)
      concatCheckerFrameworkClasspath(commandline);

      // Add main class
      commandline.createArg().setValue("com.sun.tools.javac.Main");

      // Get classpath string for file-based argument (for javac)
      String classpath = getClasspathString();
      if (classpath != null && !classpath.isEmpty()) {
        cpFofn = PluginUtil.writeTmpCpFile("CFPlugin-maven-cp", true, classpath);
        commandline.createArg().setValue(PluginUtil.fileArgToStr(cpFofn));
      }

      // Add annotated JDK to bootclasspath if needed (for Java 8 with certain CF versions)
      // This must be added BEFORE -processorpath and -processor so Checker Framework can detect it
      addAnnotatedJdkOnDemand(commandline);

      concatProcessorPath(commandline);

      concatAnnotationProcessor(commandline);

      // only process annotations, do not generate class files (to avoid
      // overwriting compilation results)
      if (procOnly) {
        commandline.createArg().setValue("-proc:only");
      }

      // Automatically add suppressWarnings for Lombok if needed (before concatExtraJavacArgs)
      if (extraJavacArgs == null) {
        extraJavacArgs = new ArrayList<>();
      }
      lombokIntegration.addLombokSuppressWarningsIfNeeded(extraJavacArgs);
      
      concatExtraJavacArgs(commandline);

      // Collect all source files (.java)
      List<String> sourceFiles = getAllJavaSourceFiles(sources);
      if (sourceFiles.isEmpty()) {
        log.info("No source files found to check.");
        return;
      }

      // Write source files to temporary file and use @file syntax
      srcFofn = PluginUtil.writeTmpSrcFofn("CFPlugin-maven-src", true, sourceFiles);
      commandline.createArg().setValue(PluginUtil.fileArgToStr(srcFofn));

      // Execute command
      String[] commandArray = commandline.getCommandline();
      String commandString = formatCommandForLogging(commandArray);
      log.debug("Executing command:\n" + commandString);

      new JavacIOExecutor(executable).executeCommandLine(commandline, log, failOnError);

      log.info("Checker Framework analysis completed successfully.");

    } catch (Exception e) {
      throw new MojoExecutionException("Error running Checker Framework", e);
    } finally {
      // Clean up temporary files
      if (srcFofn != null && srcFofn.exists()) {
        srcFofn.delete();
      }
      if (cpFofn != null && cpFofn.exists()) {
        cpFofn.delete();
      }
    }
  }

  private boolean skipCheckerFramework() {
    final Log log = getLog();
    if (skip) {
      log.info("Execution is skipped");
      return true;
    } else if ("pom".equals(project.getPackaging())) {
      log.info("Execution is skipped for project with packaging 'pom'");
      return true;
    }
    return false;
  }

  /**
   * Collects source roots for checking. Includes both main and test sources unless excludeTests is
   * true. If delombok output directory exists, uses it instead of original source roots.
   *
   * @param lombokIntegration The Lombok integration handler
   * @return List of source root directories
   */
  private List<String> collectSourceRoots(LombokIntegration lombokIntegration) {
    final Log log = getLog();
    List<String> sources = new ArrayList<>();
    
    // Check if delombok output directory exists and should be used
    File delombokOutputDir = lombokIntegration.getDelombokOutputDirectory();
    if (delombokOutputDir != null && delombokOutputDir.exists()) {
      log.info("Using delombok output directory: " + delombokOutputDir.getAbsolutePath());
      sources.add(delombokOutputDir.getAbsolutePath());
      
      // For test sources, check if there's a test delombok output directory
      if (!excludeTests) {
        File testDelombokOutputDir = lombokIntegration.getDelombokTestOutputDirectory();
        if (testDelombokOutputDir != null && testDelombokOutputDir.exists()) {
          log.info("Using delombok test output directory: " + testDelombokOutputDir.getAbsolutePath());
          sources.add(testDelombokOutputDir.getAbsolutePath());
        } else {
          // Fallback to original test sources if delombok test output doesn't exist
          sources.addAll(project.getTestCompileSourceRoots());
        }
      }
    } else {
      // Use original source roots if delombok output doesn't exist
      sources.addAll(project.getCompileSourceRoots());
      if (!excludeTests) {
        sources.addAll(project.getTestCompileSourceRoots());
      } else {
        log.info("Excluding test sources from checking.");
      }
    }
    
    return sources;
  }

  private void concatJavaPath(Commandline commandline) {
    commandline.setExecutable(PathUtils.getExecutablePath(executable, toolchainManager, session));
  }

  /**
   * Gets the classpath string for the current project configuration. Returns null if classpath is
   * empty.
   */
  private String getClasspathString() throws DependencyResolutionRequiredException {
    // Concatenate Classpath (dependency package path)
    // If we're checking test sources, use test classpath; otherwise use compile
    // classpath
    List<String> classpathElements;
    boolean hasTestSources = !project.getTestCompileSourceRoots().isEmpty();

    if (!excludeTests && hasTestSources) {
      // If we have test sources and not excluding them, use test classpath
      // which includes both compile and test dependencies
      try {
        classpathElements = project.getTestClasspathElements();
      } catch (DependencyResolutionRequiredException e) {
        // Fallback to compile classpath if test classpath is not available
        classpathElements = project.getCompileClasspathElements();
      }
    } else {
      // Only checking main sources, use compile classpath
      classpathElements = project.getCompileClasspathElements();
    }

    if (classpathElements.isEmpty()) {
      return null;
    }
    return String.join(File.pathSeparator, classpathElements);
  }

  private void concatProcessorPath(Commandline commandline) {
    final Log log = getLog();
    if (checkerFrameworkJar == null || checkerQualJar == null) {
      log.error("Could not find Checker Framework JARs");
      return;
    }
    final String ProcessorPath =
        checkerFrameworkJar.getAbsolutePath()
            + File.pathSeparator
            + checkerQualJar.getAbsolutePath();
    commandline.createArg().setValue("-processorpath");
    commandline.createArg().setValue(ProcessorPath);
  }

  private void concatAnnotationProcessor(Commandline commandline) {
    if (annotationProcessors == null || annotationProcessors.isEmpty()) {
      return;
    }
    commandline.createArg().setValue("-processor");
    commandline.createArg().setValue(String.join(",", annotationProcessors));
  }

  private void concatExtraJavacArgs(Commandline commandline) {
    if (extraJavacArgs != null && !extraJavacArgs.isEmpty()) {
      for (String arg : extraJavacArgs) {
        commandline.createArg().setValue(arg);
      }
    }
  }

  private List<String> getAllJavaSourceFiles(List<String> sources) throws IOException {
    List<String> files = new ArrayList<>();
    for (String sourceRoot : sources) {
      Path rootPath = Paths.get(sourceRoot);
      if (Files.exists(rootPath)) {
        try (Stream<Path> walk = Files.walk(rootPath)) {
          List<String> javaFiles =
              walk.filter(p -> p.toString().endsWith(".java"))
                  .map(Path::toString)
                  .collect(Collectors.toList());
          files.addAll(javaFiles);
        }
      }
    }
    return files;
  }

  private boolean isJava9OrLater() {
    // Java 9 and later have version number >= 9
    return jvmVersionNumber >= 9;
  }

  private void addJava9Args(Commandline commandline) {
    String[] exports = {
      "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
      "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    };
    for (String export : exports) {
      commandline.createArg().setValue("--add-exports=" + export);
    }
    commandline
        .createArg()
        .setValue("--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");
  }

  /**
   * Checks if annotated JDK is needed based on Java version and Checker Framework version.
   * Annotated JDK is required for Java 8 when Checker Framework version <= 3.3.
   *
   * @return true if annotated JDK is needed, false otherwise
   */
  private boolean needsAnnotatedJdk() {    
    // Only needed for Java 8
    if (jvmVersionNumber != 8 || javaSourceVersionNumber != 8) {
      return false;
    }

    int[] version = PluginUtil.parseVersion(checkerFrameworkVersion);
    if (version == null) {
      // If version is not available, default to not requiring annotated JDK
      // (most recent CF versions don't need it)
      return false;
    }

    // Annotated JDK is needed for CF version <= 3.3
    // i.e., major < 3 or (major == 3 && minor <= 3)
    int majorVersion = version[0];
    int minorVersion = version[1];
    return majorVersion < 3 || (majorVersion == 3 && minorVersion <= 3);
  }

  /**
   * Adds annotated JDK to the command line on demand. This is required when running Java 8 code
   * on Java 8 JVM with Checker Framework <= 3.3.
   *
   * @param commandline The command line to add annotated JDK to
   */
  private void addAnnotatedJdkOnDemand(Commandline commandline) {
    if (!needsAnnotatedJdk()) {
      return;
    }

    final Log log = getLog();
    if (annotatedJdkJar != null && annotatedJdkJar.exists()) {
      String annotatedJdkPath = annotatedJdkJar.getAbsolutePath();
      // Note: -Xbootclasspath/p is a compiler argument, not a JVM argument
      commandline.createArg().setValue("-Xbootclasspath/p:" + annotatedJdkPath);
      log.debug("Added annotated JDK to bootclasspath: " + annotatedJdkPath);
    } else {
      log.warn(
          "Annotated JDK (jdk8) is required but not found. The Checker Framework may not work correctly on Java 8.");
    }
  }

  /**
   * Adds Error Prone javac to the command line on demand. This is required when running Java 8 code
   * on Java 8 JVM with Checker Framework >= 3.0 or Checker Framework >= 2.11.
   *
   * @param commandline The command line to add Error Prone javac to
   */
  private void addErrorProneJavacOnDemand(Commandline commandline) {
    if (jvmVersionNumber != 8 || javaSourceVersionNumber != 8) {
      return;
    }
    final Log log = getLog();
    boolean needErrorProneJavac = false;
    int[] version = PluginUtil.parseVersion(checkerFrameworkVersion);
    if (version == null) {
      // If version is not available, default to requiring Error Prone javac
      needErrorProneJavac = true;
      log.warn(
          "Checker Framework version not available, defaulting to requiring Error Prone javac");
    } else {
      // Check if Error Prone javac is needed based on version
      // Error Prone javac is needed for CF >= 3.0 or (CF == 2.x && minor >= 11)
      int majorVersion = version[0];
      int minorVersion = version[1];
      needErrorProneJavac = majorVersion >= 3 || (majorVersion == 2 && minorVersion >= 11);
    }

    if (!needErrorProneJavac) {
      return;
    }

    File errorProneJavacJar =
        PathUtils.getErrorProneJavacJar(project, repositorySystem, localRepository, session, log);
    if (errorProneJavacJar != null && errorProneJavacJar.exists()) {
      String errorProneJavacPath = errorProneJavacJar.getAbsolutePath();
      commandline.createArg().setValue("-Xbootclasspath/p:" + errorProneJavacPath);
    } else {
      log.warn(
          "Error Prone javac JAR is required but not found. The Checker Framework may not work correctly on Java 8.");
    }
  }

  /**
   * Adds the Checker Framework JARs to the classpath for the java command. This is needed when
   * using java command instead of javac.
   */
  private void concatCheckerFrameworkClasspath(Commandline commandline) {
    final Log log = getLog();
    if (checkerFrameworkJar == null || checkerQualJar == null) {
      log.error("Could not find Checker Framework JARs");
      return;
    }
    final String checkerClasspath =
        checkerFrameworkJar.getAbsolutePath()
            + File.pathSeparator
            + checkerQualJar.getAbsolutePath();
    commandline.createArg().setValue("-cp");
    commandline.createArg().setValue(checkerClasspath);
  }

  /**
   * Locate the Checker Framework JAR files for the checker and checker-qual artifacts. The priority
   * is 1. From project dependencies 2. From local repository 3. From remote repositories
   */
  private void locateArtifacts() {
    final Log log = getLog();
    checkerFrameworkJar =
        PathUtils.getFrameworkJar(
            "checker",
            project,
            repositorySystem,
            localRepository,
            session,
            checkerFrameworkVersion,
            log);
    checkerQualJar =
        PathUtils.getFrameworkJar(
            "checker-qual",
            project,
            repositorySystem,
            localRepository,
            session,
            checkerFrameworkVersion,
            log);

    // Locate annotated JDK if needed (for Java 8 with certain CF versions)
    if (needsAnnotatedJdk()) {
      annotatedJdkJar =
          PathUtils.getAnnotatedJdkJar(
              project,
              repositorySystem,
              localRepository,
              session,
              checkerFrameworkVersion,
              log);
      if (annotatedJdkJar == null || !annotatedJdkJar.exists()) {
        log.warn(
            "Annotated JDK (jdk8) is required but not found. The Checker Framework may not work correctly on Java 8.");
      }
    }
  }

  private void loadJavaSourceVersionAndJvmVersion() throws MojoFailureException {
    // Get Java source version number from pom.xml configuration
    // (maven.compiler.source/target)
    javaSourceVersionNumber = PathUtils.getJavaSourceVersionNumber(project);
    if (javaSourceVersionNumber < 8) {
      getLog().error("Java source version must be at least 8");
      throw new MojoFailureException("Java source version must be at least 8");
    }

    // Get actual JVM version number that is running the Maven build
    // Priority: 1. From Toolchain 2. From system property
    jvmVersionNumber = PathUtils.getJvmVersionNumber(toolchainManager, session);
    if (jvmVersionNumber < 8) {
      getLog().error("JVM version must be at least 8");
      throw new MojoFailureException("JVM version must be at least 8");
    }
  }

  /**
   * Formats a command array into a multi-line string with backslash continuation, making it easy to
   * read and copy-paste into a terminal.
   *
   * @param commandArray The command array to format
   * @return A formatted multi-line string that can be directly executed in a terminal
   */
  private String formatCommandForLogging(String[] commandArray) {
    if (commandArray == null || commandArray.length == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < commandArray.length; i++) {
      String arg = commandArray[i];
      // Escape spaces and special characters if needed
      if (arg.contains(" ") || arg.contains("\"") || arg.contains("'")) {
        arg = "\"" + arg.replace("\"", "\\\"") + "\"";
      }
      sb.append(arg);
      // Add backslash continuation for all but the last argument
      if (i < commandArray.length - 1) {
        sb.append(" \\\n  ");
      }
    }
    return sb.toString();
  }
}
