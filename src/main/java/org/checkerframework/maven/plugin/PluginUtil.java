package org.checkerframework.maven.plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for creating temporary files used with javac's @file syntax. This helps avoid
 * command line length limits when passing many source files or long classpaths.
 */
public class PluginUtil {

  /**
   * Writes a list of source file paths to a temporary file (File Of File Names - FOFN). Each file
   * path is written on a separate line as an absolute path.
   *
   * @param prefix The prefix for the temporary file name
   * @param deleteOnExit Whether to delete the file on JVM exit
   * @param sourceFiles List of source file paths (as strings)
   * @return The created temporary file
   * @throws IOException If an I/O error occurs
   */
  public static File writeTmpSrcFofn(String prefix, boolean deleteOnExit, List<String> sourceFiles)
      throws IOException {
    File tmpFile = File.createTempFile(prefix, ".src_files");
    if (deleteOnExit) {
      tmpFile.deleteOnExit();
    }
    try (BufferedWriter bw = new BufferedWriter(new FileWriter(tmpFile))) {
      for (String sourceFile : sourceFiles) {
        bw.write(new File(sourceFile).getAbsolutePath());
        bw.newLine();
      }
      bw.flush();
    }
    return tmpFile;
  }

  /**
   * Writes classpath to a temporary file in the format expected by javac's @file syntax. The file
   * contains: -cp "classpath_string"
   *
   * @param prefix The prefix for the temporary file name
   * @param deleteOnExit Whether to delete the file on JVM exit
   * @param classpath The classpath string
   * @return The created temporary file
   * @throws IOException If an I/O error occurs
   */
  public static File writeTmpCpFile(String prefix, boolean deleteOnExit, String classpath)
      throws IOException {
    File tmpFile = File.createTempFile(prefix, ".classpath");
    if (deleteOnExit) {
      tmpFile.deleteOnExit();
    }
    try (BufferedWriter bw = new BufferedWriter(new FileWriter(tmpFile))) {
      // Wrap classpath in quotes if it contains spaces
      String wrappedClasspath = wrapArg(classpath);
      bw.write("-cp " + wrappedClasspath);
      bw.flush();
    }
    return tmpFile;
  }

  /**
   * Converts a file path to the @file syntax used by javac.
   *
   * @param fileArg The file to reference
   * @return The @file path string
   */
  public static String fileArgToStr(File fileArg) {
    return "@" + fileArg.getAbsolutePath();
  }

  /**
   * Wraps an argument in quotes if it contains spaces.
   *
   * @param arg The argument to wrap
   * @return The wrapped argument
   */
  private static String wrapArg(String arg) {
    if (arg.contains(" ")) {
      return "\"" + escapeQuotes(arg) + "\"";
    }
    return arg;
  }

  /**
   * Escapes quotes in a string for use in quoted arguments.
   *
   * @param toEscape The string to escape
   * @return The escaped string
   */
  private static String escapeQuotes(String toEscape) {
    return toEscape.replace("\"", "\\\"");
  }

  /**
   * Parses a version string (e.g., "3.53.0") and extracts the major and minor version numbers.
   *
   * @param versionString The version string to parse
   * @return An array of two integers: [majorVersion, minorVersion], or null if parsing fails
   */
  public static int[] parseVersion(String versionString) {
    if (versionString == null || versionString.isEmpty()) {
      return null;
    }
    try {
      String[] versionParts = versionString.split("\\.");
      if (versionParts.length >= 2) {
        int majorVersion = Integer.parseInt(versionParts[0]);
        int minorVersion = Integer.parseInt(versionParts[1]);
        return new int[] {majorVersion, minorVersion};
      }
    } catch (NumberFormatException e) {
      // Return null if parsing fails
    }
    return null;
  }
}
