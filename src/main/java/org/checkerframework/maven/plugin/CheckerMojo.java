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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(
    name = "check",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
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

  @Parameter(property = "executable", defaultValue = "javac")
  private String executable;

  /**
   * A list of inclusion filters for the compiler. When CheckersMojo scans the
   * "${compileSourceRoot}" directory for files it will only include those files that match one of
   * the specified inclusion patterns. If no patterns are included then
   * PathUtils.DEFAULT_INCLUSION_PATTERN is used
   */
  @Parameter(property = "includes")
  private final Set<String> includes = new HashSet<>();

  /**
   * A list of exclusion filters for the compiler. When CheckersMojo scans the
   * "${compileSourceRoot}" directory for files it will only include those file that DO NOT match
   * any of the specified exclusion patterns.
   */
  @Parameter(property = "excludes")
  private final Set<String> excludes = new HashSet<>();

  /**
   * Whether to only process annotations, do not generate class files (to avoid overwriting
   * compilation results)
   */
  @Parameter(property = "procOnly", defaultValue = "true")
  private boolean procOnly;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    final Log log = getLog();
    if (skip) {
      log.info("Execution is skipped");
      return;
    } else if ("pom".equals(project.getPackaging())) {
      log.info("Execution is skipped for project with packaging 'pom'");
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
    // Priority: 1. Explicitly configured version 2. Version from project dependencies 3. Default
    // version
    if (checkerFrameworkVersion == null || checkerFrameworkVersion.isEmpty()) {
      String versionFromDeps = PathUtils.getCheckerFrameworkVersionFromDependencies(project, log);
      if (versionFromDeps != null && !versionFromDeps.isEmpty()) {
        checkerFrameworkVersion = versionFromDeps;
      } else {
        checkerFrameworkVersion = DEFAULT_CHECKER_FRAMEWORK_VERSION;
      }
    }

    log.info("Starting Checker Framework analysis with version: " + checkerFrameworkVersion);
    // TODO: fix it
    //    final List<String> sources = PathUtils.scanForSources(compileSourceRoots, includes,
    // excludes);
    final List<String> sources = project.getCompileSourceRoots();

    if (sources.isEmpty()) {
      log.info("No source files found.");
      return;
    }

    locateArtifacts();

    try {
      // Prepare javac command
      // TODO: use commandLine in codehaus
      List<String> command = new ArrayList<>();

      concatJavacPath(command);

      concatClasspath(command);

      concatProcessorPath(command);

      concatAnnotationProcessor(command);

      // only process annotations, do not generate class files (to avoid
      // overwriting compilation results)
      if (procOnly) {
        command.add("-proc:only");
      }

      // Automatically add security parameters for Java 9+ (saves you from the huge configuration in
      // pom.xml)
      if (isJava9OrLater()) {
        addJava9Args(command);
      }

      // Collect all source files (.java)
      List<String> sourceFiles = getAllJavaSourceFiles(sources);
      if (sourceFiles.isEmpty()) {
        log.info("No source files found to check.");
        return;
      }
      command.addAll(sourceFiles);

      // Execute command
      log.debug("Executing command: " + String.join(" ", command));
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true); // Merge error output to standard output

      Process process = pb.start();

      // Print javac output to Maven log
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.contains("error:")) {
            log.error(line); // Highlight errors
          } else {
            log.info(line);
          }
        }
      }

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new MojoFailureException("Checker Framework found errors.");
      }

      log.info("Checker Framework analysis completed successfully.");

    } catch (Exception e) {
      throw new MojoExecutionException("Error running Checker Framework", e);
    }
  }

  // --- Helper methods ---

  /**
   * Find the Checker Framework JAR file path Returns the paths of the checker and checker-qual JAR
   * files, connected by the system path separator First try to find it from the project
   * dependencies, if not found, resolve it from the local repository
   */
  private String findCheckerFrameworkJar() {
    final Log log = getLog();

    // Get checker jar
    File checkerJarFile =
        PathUtils.getFrameworkJar(
            "checker", project, repositorySystem, localRepository, checkerFrameworkVersion, log);

    // Get checker-qual jar
    File checkerQualJarFile =
        PathUtils.getFrameworkJar(
            "checker-qual",
            project,
            repositorySystem,
            localRepository,
            checkerFrameworkVersion,
            log);

    // If both jars are found, return the connected path
    if (checkerJarFile != null && checkerQualJarFile != null) {
      return checkerJarFile.getAbsolutePath()
          + File.pathSeparator
          + checkerQualJarFile.getAbsolutePath();
    }

    // If only the checker jar is found, return it (it can at least work, although some
    // classes may be missing)
    if (checkerJarFile != null) {
      log.warn(
          "Only found checker jar, checker-qual jar is missing. Some classes may not be found.");
      return checkerJarFile.getAbsolutePath();
    }

    return null;
  }

  private void concatJavacPath(List<String> command) {
    command.add(PathUtils.getExecutablePath(executable, toolchainManager, session));
  }

  private void concatClasspath(List<String> command) throws DependencyResolutionRequiredException {
    // Concatenate Classpath (dependency package path)
    List<String> classpathElements = project.getCompileClasspathElements();

    if (classpathElements.isEmpty()) {
      return;
    }
    String classpath = String.join(File.pathSeparator, classpathElements);
    command.add("-cp");
    command.add(classpath);
  }

  private void concatProcessorPath(List<String> command) {
    final Log log = getLog();
    // TODO: fix it
    String checkerFrameworkJar = findCheckerFrameworkJar();
    if (checkerFrameworkJar != null) {
      command.add("-processorpath");
      command.add(checkerFrameworkJar);
      log.debug("Using processorpath: " + checkerFrameworkJar);
    } else {
      log.warn("Could not find Checker Framework JAR. Trying to use classpath instead.");
    }
  }

  private void concatAnnotationProcessor(List<String> command) {
    if (annotationProcessors == null || annotationProcessors.isEmpty()) {
      return;
    }
    command.add("-processor");
    command.add(String.join(",", annotationProcessors));
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
    String version = System.getProperty("java.version");
    return !version.startsWith(
        "1."); // Before 1.8, it starts with 1., after 9, it is directly 9, 11, 21...
  }

  private void addJava9Args(List<String> command) {
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
      command.add("-J--add-exports=" + export);
    }
    command.add("-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");
  }

  private void locateArtifacts() {}
}
