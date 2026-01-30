package org.checkerframework.maven.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link PluginUtil}. */
class PluginUtilTest {

  @TempDir File tempDir;

  @Test
  void fileArgToStr_returnsAtPrefixWithAbsolutePath() {
    File file = new File(tempDir, "test.txt");
    String result = PluginUtil.fileArgToStr(file);
    assertEquals("@" + file.getAbsolutePath(), result);
  }

  @Test
  void exists_nullReturnsFalse() {
    assertFalse(PluginUtil.exists(null));
  }

  @Test
  void exists_fileNotExistsReturnsFalse() {
    assertFalse(PluginUtil.exists(new File(tempDir, "nonexistent")));
  }

  @Test
  void exists_fileExistsReturnsTrue() throws IOException {
    File file = new File(tempDir, "exists.txt");
    assertTrue(file.createNewFile());
    assertTrue(PluginUtil.exists(file));
  }

  @Test
  void parseVersion_nullReturnsNull() {
    assertNull(PluginUtil.parseVersion(null));
  }

  @Test
  void parseVersion_emptyReturnsNull() {
    assertNull(PluginUtil.parseVersion(""));
  }

  @Test
  void parseVersion_validVersionReturnsMajorMinor() {
    assertArrayEquals(new int[] {3, 53}, PluginUtil.parseVersion("3.53.0"));
    assertArrayEquals(new int[] {2, 11}, PluginUtil.parseVersion("2.11.0"));
    assertArrayEquals(new int[] {1, 0}, PluginUtil.parseVersion("1.0"));
  }

  @Test
  void parseVersion_invalidReturnsNull() {
    assertNull(PluginUtil.parseVersion("not-a-version"));
    assertNull(PluginUtil.parseVersion("1"));
    assertNull(PluginUtil.parseVersion("."));
  }

  @Test
  void writeTmpSrcFofn_createsFileWithAbsolutePaths() throws IOException {
    File src1 = new File(tempDir, "A.java");
    File src2 = new File(tempDir, "B.java");
    Files.write(src1.toPath(), "class A {}".getBytes());
    Files.write(src2.toPath(), "class B {}".getBytes());
    List<String> paths = Arrays.asList(src1.getPath(), src2.getPath());

    File fofn = PluginUtil.writeTmpSrcFofn("test-src", true, paths);
    assertNotNull(fofn);
    assertTrue(fofn.exists());

    String content = Files.readString(fofn.toPath()).trim();
    assertTrue(content.contains(new File(paths.get(0)).getAbsolutePath()));
    assertTrue(content.contains(new File(paths.get(1)).getAbsolutePath()));
  }

  @Test
  void writeTmpSrcFofn_emptyList_createsEmptyFile() throws IOException {
    File fofn = PluginUtil.writeTmpSrcFofn("empty", true, Collections.emptyList());
    assertNotNull(fofn);
    assertEquals(0, Files.readString(fofn.toPath()).length());
  }

  @Test
  void writeTmpCpFile_createsFileWithCpArg() throws IOException {
    String cp = "/path/to/a.jar" + File.pathSeparator + "/path/to/b.jar";
    File fofn = PluginUtil.writeTmpCpFile("test-cp", true, cp);
    assertNotNull(fofn);
    assertTrue(fofn.exists());
    String content = Files.readString(fofn.toPath()).trim();
    assertTrue(content.startsWith("-cp "));
    assertTrue(content.contains("/path/to/a.jar"));
  }

  @Test
  void writeTmpCpFile_withSpaces_wrapsInQuotes() throws IOException {
    String cp = "/path with spaces/jar.jar";
    File fofn = PluginUtil.writeTmpCpFile("test-cp", true, cp);
    String content = Files.readString(fofn.toPath()).trim();
    assertTrue(content.contains("\""), "classpath with spaces should be quoted");
  }
}
