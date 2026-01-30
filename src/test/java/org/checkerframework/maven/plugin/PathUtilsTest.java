package org.checkerframework.maven.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link PathUtils}. */
@ExtendWith(MockitoExtension.class)
class PathUtilsTest {

  @Mock MavenProject project;
  @Mock ToolchainManager toolchainManager;
  @Mock MavenSession session;
  @Mock Toolchain toolchain;

  @Test
  void getCheckerFrameworkVersionFromDependencies_emptyArtifactsReturnsNull() {
    when(project.getArtifacts()).thenReturn(Collections.emptySet());
    assertNull(PathUtils.getCheckerFrameworkVersionFromDependencies(project));
  }

  @Test
  void getCheckerFrameworkVersionFromDependencies_checkerQualReturnsVersion() {
    Artifact qual = mock(Artifact.class);
    when(qual.getGroupId()).thenReturn("org.checkerframework");
    when(qual.getArtifactId()).thenReturn("checker-qual");
    when(qual.getVersion()).thenReturn("3.53.0");
    when(project.getArtifacts()).thenReturn(Set.of(qual));

    assertEquals("3.53.0", PathUtils.getCheckerFrameworkVersionFromDependencies(project));
  }

  @Test
  void getCheckerFrameworkVersionFromDependencies_otherArtifactIgnored() {
    Artifact other = mock(Artifact.class);
    when(other.getGroupId()).thenReturn("org.checkerframework");
    when(other.getArtifactId()).thenReturn("checker"); // not checker-qual
    when(project.getArtifacts()).thenReturn(Set.of(other));

    assertNull(PathUtils.getCheckerFrameworkVersionFromDependencies(project));
  }

  @Test
  void getJavaSourceVersionNumber_fromCompilerSource() {
    Properties props = new Properties();
    props.setProperty("maven.compiler.source", "11");
    when(project.getProperties()).thenReturn(props);

    assertEquals(11, PathUtils.getJavaSourceVersionNumber(project));
  }

  @Test
  void getJavaSourceVersionNumber_fromCompilerTargetWhenSourceMissing() {
    Properties props = new Properties();
    props.setProperty("maven.compiler.target", "8");
    when(project.getProperties()).thenReturn(props);

    assertEquals(8, PathUtils.getJavaSourceVersionNumber(project));
  }

  @Test
  void getJavaSourceVersionNumber_1_8Format() {
    Properties props = new Properties();
    props.setProperty("maven.compiler.source", "1.8");
    when(project.getProperties()).thenReturn(props);

    assertEquals(8, PathUtils.getJavaSourceVersionNumber(project));
  }

  @Test
  void getJavaSourceVersionNumber_missingReturnsNegativeOne() {
    when(project.getProperties()).thenReturn(new Properties());
    assertEquals(-1, PathUtils.getJavaSourceVersionNumber(project));
  }

  @Test
  void getJvmVersionNumber_fromSystemPropertyWhenNoToolchain() {
    when(toolchainManager.getToolchainFromBuildContext("jdk", session)).thenReturn(null);
    int version = PathUtils.getJvmVersionNumber(toolchainManager, session);
    // Should parse java.version system property
    assertTrue(version >= 8, "JVM version should be at least 8 when running tests");
  }

  @Test
  void getExecutablePath_nonexistentPathAndNoToolchainReturnsExecutableAsIs() {
    when(toolchainManager.getToolchainFromBuildContext("jdk", session)).thenReturn(null);
    String path = "/nonexistent/path/to/java";
    String result = PathUtils.getExecutablePath(path, toolchainManager, session);
    assertEquals(path, result);
  }

  @Test
  void getExecutablePath_toolchainReturnsFindToolResult() {
    when(toolchainManager.getToolchainFromBuildContext("jdk", session)).thenReturn(toolchain);
    when(toolchain.findTool("java")).thenReturn("/jdk/bin/java");
    String result = PathUtils.getExecutablePath("java", toolchainManager, session);
    assertEquals("/jdk/bin/java", result);
  }
}
