package org.checkerframework.maven.plugin;

import org.apache.commons.lang3.StringUtils;
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
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Component private RepositorySystem repositorySystem;

  @Parameter(defaultValue = "${localRepository}", readonly = true)
  private ArtifactRepository localRepository;

  // TODO: read real value
  @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
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

    log.info("Running Checker Framework version: " + checkerFrameworkVersion);
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

    // 1. Check configuration

    log.info("Starting Checker Framework analysis...");

    try {
      // 2. Prepare javac command
      List<String> command = new ArrayList<>();
      command.add(getJavacPath()); // Find the javac executable

      // 3. Concatenate Classpath (dependency package path)
      List<String> classpathElements = project.getCompileClasspathElements();
      if (!classpathElements.isEmpty()) {
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpathElements));
      }

      // 3.5 Add processorpath (annotation processor classpath)
      String processorPath = findCheckerFrameworkJar();
      if (processorPath != null) {
        command.add("-processorpath");
        command.add(processorPath);
        log.debug("Using processorpath: " + processorPath);
      } else {
        log.warn("Could not find Checker Framework JAR. Trying to use classpath instead.");
      }

      // 4. Set Processor (Checkers)
      command.add("-processor");
      command.add(String.join(",", annotationProcessors));

      // 5. Key parameter: only process annotations, do not generate class files (to avoid overwriting compilation results)
      command.add("-proc:only");

      // 6. Automatically add security parameters for Java 9+ (saves you from the huge configuration in pom.xml)
      if (isJava9OrLater()) {
        addJava9Args(command);
      }

      // 7. Collect all source files (.java)
      List<String> sourceFiles = getAllJavaSourceFiles();
      if (sourceFiles.isEmpty()) {
        log.info("No source files found to check.");
        return;
      }
      command.addAll(sourceFiles);

      // 8. Execute command
      log.debug("Executing command: " + String.join(" ", command));
      log.info("after executing command: " + command);
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
    String checkerJarPath = null;
    String checkerQualJarPath = null;
    final Log log = getLog();

    // Method 1: Find from project dependencies
    Set<Artifact> artifacts = project.getArtifacts();
    for (Artifact artifact : artifacts) {
      if ("org.checkerframework".equals(artifact.getGroupId())) {
        if ("checker".equals(artifact.getArtifactId())) {
          File artifactFile = artifact.getFile();
          if (artifactFile != null && artifactFile.exists()) {
            checkerJarPath = artifactFile.getAbsolutePath();
            log.debug("Found Checker Framework in project dependencies: " + checkerJarPath);
          }
        } else if ("checker-qual".equals(artifact.getArtifactId())) {
          File artifactFile = artifact.getFile();
          if (artifactFile != null && artifactFile.exists()) {
            checkerQualJarPath = artifactFile.getAbsolutePath();
            log.debug("Found Checker Qual in project dependencies: " + checkerQualJarPath);
          }
        }
      }
    }

    // If two jars are found from the dependencies, return directly
    if (checkerJarPath != null && checkerQualJarPath != null) {
      return checkerJarPath + File.pathSeparator + checkerQualJarPath;
    }

    // Method 2: Resolve from local repository
    try {
      String version =
          checkerFrameworkVersion != null && !checkerFrameworkVersion.isEmpty()
              ? checkerFrameworkVersion
              : "3.53.0"; // Default version

      // Find checker jar
      if (checkerJarPath == null) {
        Artifact checkerArtifact =
            repositorySystem.createArtifact(
                "org.checkerframework", "checker", version, null, "jar");

        File artifactFile =
            new File(localRepository.getBasedir(), localRepository.pathOf(checkerArtifact));

        if (artifactFile.exists()) {
          checkerJarPath = artifactFile.getAbsolutePath();
          log.debug("Found Checker Framework in local repository: " + checkerJarPath);
        } else {
          log.warn("Checker Framework JAR not found at: " + artifactFile.getAbsolutePath());
        }
      }

      // Find checker-qual jar
      if (checkerQualJarPath == null) {
        Artifact checkerQualArtifact =
            repositorySystem.createArtifact(
                "org.checkerframework", "checker-qual", version, null, "jar");

        File artifactFile =
            new File(localRepository.getBasedir(), localRepository.pathOf(checkerQualArtifact));

        if (artifactFile.exists()) {
          checkerQualJarPath = artifactFile.getAbsolutePath();
          log.debug("Found Checker Qual in local repository: " + checkerQualJarPath);
        } else {
          log.warn("Checker Qual JAR not found at: " + artifactFile.getAbsolutePath());
        }
      }

      // If two jars are found, return the connected path
      if (checkerJarPath != null && checkerQualJarPath != null) {
        return checkerJarPath + File.pathSeparator + checkerQualJarPath;
      }
    } catch (Exception e) {
      log.debug("Failed to resolve Checker Framework from local repository: " + e.getMessage());
    }

    // Method 3: Try to get from the class loader (backup plan, only returns the checker
    // jar)
    if (checkerJarPath == null) {
      try {
        Class<?> checkerClass =
            Class.forName("org.checkerframework.checker.nullness.NullnessChecker");
        String classPath =
            checkerClass.getProtectionDomain().getCodeSource().getLocation().getPath();
        // Handle URL encoding and file: prefix
        if (classPath.startsWith("file:")) {
          classPath = classPath.substring(5);
        }
        // URL decode
        classPath = java.net.URLDecoder.decode(classPath, "UTF-8");
        checkerJarPath = classPath;
        log.debug("Found Checker Framework via classloader: " + checkerJarPath);
      } catch (Exception e) {
        log.debug("Could not find Checker Framework via classloader: " + e.getMessage());
      }
    }

    // If only the checker jar is found, return it (it can at least work, although some
      // classes may be missing)
    if (checkerJarPath != null) {
      if (checkerQualJarPath != null) {
        return checkerJarPath + File.pathSeparator + checkerQualJarPath;
      } else {
        log.warn(
            "Only found checker jar, checker-qual jar is missing. Some classes may not be found.");
        return checkerJarPath;
      }
    }

    return null;
  }

  private String getJavacPath() {
    String javaHome = System.getProperty("java.home");
    // Simple processing: assume javac is in the bin directory
    // In actual production, more rigorous search logic may be required (e.g., handling
    // Windows .exe suffix)
    return Paths.get(javaHome, "bin", "javac").toString();
  }

  private List<String> getAllJavaSourceFiles() throws IOException {
    List<String> files = new ArrayList<>();
    for (String sourceRoot : (List<String>) project.getCompileSourceRoots()) {
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
    return !version.startsWith("1."); // Before 1.8, it starts with 1., after 9, it is directly 9, 11, 21...
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
}
