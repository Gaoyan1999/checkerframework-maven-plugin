package org.checkerframework.maven.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Handles Lombok integration for the Checker Framework Maven plugin. This includes detecting Lombok
 * usage, finding delombok output directories, and adding necessary compiler arguments.
 */
public class LombokIntegration {
  private final MavenProject project;
  private final Log log;
  private final List<String> annotationProcessors;
  private final boolean suppressLombokWarnings;
  private File cachedDelombokOutputDir;

  /**
   * Creates a new LombokIntegration instance.
   *
   * @param project The Maven project
   * @param log The logger
   * @param annotationProcessors The list of annotation processors
   * @param suppressLombokWarnings Whether to suppress warnings from Lombok-generated code
   */
  public LombokIntegration(
      MavenProject project,
      Log log,
      List<String> annotationProcessors,
      boolean suppressLombokWarnings) {
    this.project = project;
    this.log = log;
    this.annotationProcessors = annotationProcessors;
    this.suppressLombokWarnings = suppressLombokWarnings;
  }

  /**
   * Handles Lombok integration: detects Lombok usage, checks for problematic checkers, and logs
   * warnings if needed.
   */
  public void handleIntegration() {
    if (!isLombokUsed()) {
      return;
    }

    log.info("Lombok detected in project. Checking for delombok output directory.");

    // Check if Object Construction or Called Methods Checker is used
    boolean hasProblematicChecker = hasObjectConstructionOrCalledMethodsChecker();
    if (hasProblematicChecker) {
      log.warn(
          "The Object Construction or Called Methods Checker was enabled, but Lombok config generation is disabled. "
              + "Ensure that your lombok.config file contains 'lombok.addLombokGeneratedAnnotation = true', "
              + "or all warnings related to misuse of Lombok builders will be disabled.");
    }

    // Check if delombok output directory exists and cache it
    cachedDelombokOutputDir = findDelombokOutputDirectory();
    if (PluginUtil.exists(cachedDelombokOutputDir)) {
      log.info("Found delombok output directory: " + cachedDelombokOutputDir.getAbsolutePath());
      log.info(
          "Note: Make sure delombok is configured to generate @Generated annotations. "
              + "You may need to configure lombok-maven-plugin with appropriate format options.");
    } else {
      log.warn(
          "Lombok is detected but delombok output directory not found. "
              + "The Checker Framework will check original source files, which may contain Lombok annotations.");
    }
  }

  /**
   * Checks if Lombok is used in the project by checking dependencies and plugins.
   *
   * @return true if Lombok is detected, false otherwise
   */
  public boolean isLombokUsed() {
    // Check dependencies for lombok
    boolean hasLombokDependency =
        project.getArtifacts().stream()
            .anyMatch(
                a ->
                    "org.projectlombok".equals(a.getGroupId())
                        && "lombok".equals(a.getArtifactId()));

    if (hasLombokDependency) {
      return true;
    }

    // Check for lombok-maven-plugin
    if (project.getBuild() != null && project.getBuild().getPlugins() != null) {
      for (Plugin plugin : project.getBuild().getPlugins()) {
        if ("org.projectlombok".equals(plugin.getGroupId())
            && "lombok-maven-plugin".equals(plugin.getArtifactId())) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Checks if Object Construction or Called Methods Checker is in the annotation processors list.
   *
   * @return true if either checker is found, false otherwise
   */
  public boolean hasObjectConstructionOrCalledMethodsChecker() {
    if (annotationProcessors == null || annotationProcessors.isEmpty()) {
      return false;
    }

    return annotationProcessors.stream()
        .anyMatch(
            processor ->
                processor.contains("ObjectConstructionChecker")
                    || processor.contains("CalledMethodsChecker"));
  }

  /**
   * Gets the delombok output directory from lombok-maven-plugin configuration. Returns cached value
   * if available, otherwise computes and caches it.
   *
   * @return The delombok output directory File, or null if not found
   */
  public File getDelombokOutputDirectory() {
    if (cachedDelombokOutputDir != null) {
      return cachedDelombokOutputDir;
    }
    // If not cached yet, compute it (shouldn't happen if handleIntegration was called first,
    // but handle gracefully)
    cachedDelombokOutputDir = findDelombokOutputDirectory();
    return cachedDelombokOutputDir;
  }

  /**
   * Computes the delombok output directory from lombok-maven-plugin configuration. Looks for the
   * outputDirectory configuration in the delombok goal execution.
   *
   * @return The delombok output directory File, or null if not found
   */
  private File findDelombokOutputDirectory() {
    if (project.getBuild() == null || project.getBuild().getPlugins() == null) {
      return null;
    }

    // Find lombok-maven-plugin
    Optional<Plugin> lombokPlugin =
        project.getBuild().getPlugins().stream()
            .filter(
                p ->
                    "org.projectlombok".equals(p.getGroupId())
                        && "lombok-maven-plugin".equals(p.getArtifactId()))
            .findFirst();

    if (!lombokPlugin.isPresent()) {
      return null;
    }

    // Get base directory and build directory for path resolution
    File baseDir = project.getBasedir();
    String buildDirectory = project.getBuild().getDirectory();
    if (buildDirectory == null) {
      buildDirectory = new File(baseDir, "target").getAbsolutePath();
    }

    // Look for delombok goal execution
    for (PluginExecution execution : lombokPlugin.get().getExecutions()) {
      if (execution.getGoals() != null && execution.getGoals().contains("delombok")) {
        // Get configuration
        Object configObj = execution.getConfiguration();
        if (configObj instanceof Xpp3Dom) {
          Xpp3Dom config = (Xpp3Dom) configObj;
          Xpp3Dom outputDirectoryNode = config.getChild("outputDirectory");
          if (outputDirectoryNode != null) {
            String outputDirectory = outputDirectoryNode.getValue();
            if (outputDirectory != null && !outputDirectory.isEmpty()) {
              File resolvedDir = resolveDelombokPath(outputDirectory, baseDir, buildDirectory);
              if (resolvedDir != null) {
                return resolvedDir;
              }
            }
          }
        }
      }
    }

    // If not found in execution, check plugin-level configuration
    Object configObj = lombokPlugin.get().getConfiguration();
    if (configObj instanceof Xpp3Dom) {
      Xpp3Dom config = (Xpp3Dom) configObj;
      Xpp3Dom outputDirectoryNode = config.getChild("outputDirectory");
      if (outputDirectoryNode != null) {
        String outputDirectory = outputDirectoryNode.getValue();
        if (outputDirectory != null && !outputDirectory.isEmpty()) {
          return resolveDelombokPath(outputDirectory, baseDir, buildDirectory);
        }
      }
    }

    return null;
  }

  /**
   * Resolves a delombok output directory path by replacing Maven properties with actual values.
   *
   * @param path The path string that may contain Maven properties
   * @param baseDir The project base directory
   * @param buildDirectory The build directory path
   * @return The resolved File, or null if path is invalid
   */
  private File resolveDelombokPath(String path, File baseDir, String buildDirectory) {
    if (path == null || path.isEmpty()) {
      return null;
    }

    // Replace Maven properties
    path = path.replace("${project.build.directory}", buildDirectory);
    path = path.replace("${project.basedir}", baseDir.getAbsolutePath());

    // Handle relative paths
    File resolvedFile = new File(path);
    if (!resolvedFile.isAbsolute()) {
      resolvedFile = new File(baseDir, path);
    }

    return resolvedFile;
  }

  /**
   * Gets the delombok test output directory. This is a placeholder for future support of test
   * delombok output.
   *
   * @return The delombok test output directory File, or null if not found
   */
  public File getDelombokTestOutputDirectory() {
    // TODO: For now, return null. In the future, this could check for test-specific delombok
    // configuration
    // or use a convention like ${project.build.directory}/delombok-test
    return null;
  }

  /**
   * Automatically adds -AsuppressWarnings=type.anno.before.modifier parameter to extraJavacArgs if
   * Lombok is used. This suppresses warnings from Lombok-generated code that has incorrect
   * annotation formatting.
   *
   * @param extraJavacArgs The list of extra javac arguments to modify
   */
  public void addLombokSuppressWarningsIfNeeded(List<String> extraJavacArgs) {
    if (!isLombokUsed() || !suppressLombokWarnings) {
      return;
    }

    // Initialize extraJavacArgs if null
    if (extraJavacArgs == null) {
      return; // Cannot modify null list
    }

    // Check if suppressWarnings is already in extraJavacArgs
    boolean alreadyAdded = false;
    for (int i = 0; i < extraJavacArgs.size(); i++) {
      String arg = extraJavacArgs.get(i);
      if (arg.startsWith("-AsuppressWarnings")) {
        // Check if type.anno.before.modifier is already included
        if (arg.contains("type.anno.before.modifier")) {
          alreadyAdded = true;
          break;
        } else {
          // Append to existing suppressWarnings
          String newArg = arg + ",type.anno.before.modifier";
          extraJavacArgs.set(i, newArg);
          alreadyAdded = true;
          log.debug("Appended type.anno.before.modifier to existing suppressWarnings: " + newArg);
          break;
        }
      }
    }

    if (!alreadyAdded) {
      extraJavacArgs.add("-AsuppressWarnings=type.anno.before.modifier");
      log.debug("Added -AsuppressWarnings=type.anno.before.modifier for Lombok-generated code");
    }
  }
}
