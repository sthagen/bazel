// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.rules.android;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.bazel.rules.android.AndroidSdkRepositoryFunction.AndroidRevision;
import com.google.devtools.build.lib.bazel.rules.android.AndroidSdkRepositoryTest.WithPlatforms;
import com.google.devtools.build.lib.bazel.rules.android.AndroidSdkRepositoryTest.WithoutPlatforms;
import com.google.devtools.build.lib.packages.RepositoryFetchException;
import com.google.devtools.build.lib.packages.util.ResourceLoader;
import com.google.devtools.build.lib.rules.android.AndroidBuildViewTestCase;
import com.google.devtools.build.lib.rules.android.AndroidSdkProvider;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/** Tests for {@link AndroidSdkRepositoryFunction}. */
@RunWith(Suite.class)
@SuiteClasses({WithoutPlatforms.class, WithPlatforms.class})
public abstract class AndroidSdkRepositoryTest extends AndroidBuildViewTestCase {
  /** Use legacy toolchain resolution. */
  @RunWith(JUnit4.class)
  public static class WithoutPlatforms extends AndroidSdkRepositoryTest {}

  /** Use platform-based toolchain resolution. */
  @RunWith(JUnit4.class)
  public static class WithPlatforms extends AndroidSdkRepositoryTest {
    @Override
    protected boolean platformBasedToolchains() {
      return true;
    }
  }

  @Before
  public void setup() throws Exception {
    scratch.file(
        "bazel_tools_workspace/tools/android/android_sdk_repository_template.bzl",
        ResourceLoader.readFromResources("tools/android/android_sdk_repository_template.bzl"));
    scratch.appendFile("WORKSPACE", "local_config_platform(name='local_config_platform')");
  }

  private void scratchPlatformsDirectories(int... apiLevels) throws Exception {
    for (int apiLevel : apiLevels) {
      scratch.dir("/sdk/platforms/android-" + apiLevel);
      scratch.file("/sdk/platforms/android-" + apiLevel + "/android.jar");
    }
  }

  private void scratchSystemImagesDirectories(String... pathFragments) throws Exception {
    for (String pathFragment : pathFragments) {
      scratch.dir("/sdk/system-images/" + pathFragment);
      scratch.file("/sdk/system-images/" + pathFragment + "/system.img");
    }
  }

  private void scratchBuildToolsDirectories(String... versions) throws Exception {
    if (versions.length == 0) {
      // Use a large version number here so that we don't have to update this test as
      // AndroidSdkRepositoryFunction.MIN_BUILD_TOOLS_REVISION increases.
      versions = new String[] {"400.0.0"};
    }
    for (String version : versions) {
      scratch.dir("/sdk/build-tools/" + version);
    }
  }

  private void scratchExtrasLibrary(
      String mavenRepoPath, String groupId, String artifactId, String version, String packaging)
      throws Exception {
    scratch.file(
        String.format(
            "/sdk/%s/%s/%s/%s/%s.pom",
            mavenRepoPath,
            groupId.replace(".", "/"),
            artifactId,
            version,
            artifactId),
        "<project>",
        "  <groupId>" + groupId + "</groupId>",
        "  <artifactId>" + artifactId + "</artifactId>",
        "  <version>" + version + "</version>",
        "  <packaging>" + packaging + "</packaging>",
        "</project>");
  }

  @Test
  public void testGeneratedAarImport() throws Exception {
    scratchPlatformsDirectories(25);
    scratchBuildToolsDirectories();
    scratchExtrasLibrary("extras/google/m2repository", "com.google.android", "foo", "1.0.0", "aar");
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();

    ConfiguredTargetAndData aarImportTarget =
        getConfiguredTargetAndData("@androidsdk//com.google.android:foo-1.0.0");
    assertThat(aarImportTarget).isNotNull();
    assertThat(aarImportTarget.getTarget().getAssociatedRule().getRuleClass())
        .isEqualTo("aar_import");
  }

  @Test
  public void testExportsExtrasLibraryArtifacts() throws Exception {
    scratchPlatformsDirectories(25);
    scratchBuildToolsDirectories();
    scratchExtrasLibrary("extras/google/m2repository", "com.google.android", "foo", "1.0.0", "aar");
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();

    ConfiguredTarget aarTarget = getConfiguredTarget(
        "@androidsdk//:extras/google/m2repository/com/google/android/foo/1.0.0/foo.aar");
    assertThat(aarTarget).isNotNull();
  }

  @Test
  public void testKnownSdkMavenRepositories() throws Exception {
    scratchPlatformsDirectories(25);
    scratchBuildToolsDirectories();
    scratchExtrasLibrary("extras/google/m2repository", "com.google.android", "a", "1.0.0", "jar");
    scratchExtrasLibrary("extras/android/m2repository", "com.android.support", "b", "1.0.0", "aar");
    scratchExtrasLibrary("extras/m2repository", "com.android.support", "c", "1.0.1", "aar");
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();

    assertThat(
            getConfiguredTarget(
                "@androidsdk//:extras/google/m2repository/com/google/android/a/1.0.0/a.jar"))
        .isNotNull();
    assertThat(
            getConfiguredTarget(
                "@androidsdk//:extras/android/m2repository/com/android/support/b/1.0.0/b.aar"))
        .isNotNull();
    assertThat(
            getConfiguredTarget(
                "@androidsdk//:extras/m2repository/com/android/support/c/1.0.1/c.aar"))
        .isNotNull();
  }

  @Test
  public void testSystemImageDirectoriesAreFound() throws Exception {
    scratchPlatformsDirectories(25);
    scratchBuildToolsDirectories();
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    scratchSystemImagesDirectories("android-25/default/armeabi-v7a", "android-24/google_apis/x86");
    invalidatePackages();

    ConfiguredTarget android25ArmFilegroup =
        getConfiguredTarget("@androidsdk//:emulator_images_android_25_arm");
    assertThat(android25ArmFilegroup).isNotNull();
    assertThat(
            artifactsToStrings(
                android25ArmFilegroup.getProvider(FilesToRunProvider.class).getFilesToRun()))
        .containsExactly(
            "src external/androidsdk/system-images/android-25/default/armeabi-v7a/system.img");

    ConfiguredTarget android24X86Filegroup =
        getConfiguredTarget("@androidsdk//:emulator_images_google_24_x86");
    assertThat(android24X86Filegroup).isNotNull();
    assertThat(
            artifactsToStrings(
                android24X86Filegroup.getProvider(FilesToRunProvider.class).getFilesToRun()))
        .containsExactly(
            "src external/androidsdk/system-images/android-24/google_apis/x86/system.img");
  }

  // Regression test for https://github.com/bazelbuild/bazel/issues/3672.
  @Test
  public void testMalformedSystemImageDirectories() throws Exception {
    scratchPlatformsDirectories(25, 26);
    scratchBuildToolsDirectories();
    scratchSystemImagesDirectories("android-25/default/armeabi-v7a", "android-O/google_apis/x86");
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();
    assertThat(getConfiguredTarget("@androidsdk//:emulator_images_android_25_arm")).isNotNull();
  }

  @Test
  public void testBuildToolsVersion() throws Exception {
    scratchPlatformsDirectories(25);
    // Use large version numbers here so that we don't have to update this test as
    // AndroidSdkRepositoryFunction.MIN_BUILD_TOOLS_REVISION increases.
    scratchBuildToolsDirectories("400.0.1", "400.0.2", "400.0.3");
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        "    build_tools_version = '400.0.2',",
        ")");
    invalidatePackages();

    ConfiguredTarget androidSdk = getConfiguredTarget("@androidsdk//:sdk");
    assertThat(androidSdk).isNotNull();
    assertThat(androidSdk.get(AndroidSdkProvider.PROVIDER).getBuildToolsVersion())
        .isEqualTo("400.0.2");
  }

  @Test
  public void testBuildToolsHighestVersionDetection() throws Exception {
    scratchPlatformsDirectories(25);
    // Use large version numbers here so that we don't have to update this test as
    // AndroidSdkRepositoryFunction.MIN_BUILD_TOOLS_REVISION increases.
    scratchBuildToolsDirectories("400.0.1", "400.0.2");
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        "    api_level = 25,",
        ")");
    invalidatePackages();

    ConfiguredTarget androidSdk = getConfiguredTarget("@androidsdk//:sdk");
    assertThat(androidSdk).isNotNull();
    assertThat(androidSdk.get(AndroidSdkProvider.PROVIDER).getBuildToolsVersion())
        .isEqualTo("400.0.2");
  }

  @Test
  public void testApiLevelHighestVersionDetection() throws Exception {
    scratchPlatformsDirectories(24, 25, 23);
    scratchBuildToolsDirectories();
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();

    ConfiguredTarget androidSdk = getConfiguredTarget("@androidsdk//:sdk");
    assertThat(androidSdk).isNotNull();
    assertThat(androidSdk.get(AndroidSdkProvider.PROVIDER).getAndroidJar().getExecPathString())
        .isEqualTo("external/androidsdk/platforms/android-25/android.jar");
  }

  @Test
  public void testMultipleAndroidSdkApiLevels() throws Exception {
    int[] apiLevels = {23, 24, 25};
    scratchPlatformsDirectories(apiLevels);
    scratchBuildToolsDirectories();
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        "    api_level = 24,",
        ")");
    invalidatePackages();

    for (int apiLevel : apiLevels) {
      ConfiguredTarget androidSdk = getConfiguredTarget("@androidsdk//:sdk-" + apiLevel);
      assertThat(androidSdk).isNotNull();
      assertThat(androidSdk.get(AndroidSdkProvider.PROVIDER).getAndroidJar().getExecPathString())
          .isEqualTo(
              String.format("external/androidsdk/platforms/android-%d/android.jar", apiLevel));
    }
  }

  @Test
  public void testMissingApiLevel() throws Exception {
    scratchPlatformsDirectories(24);
    scratchBuildToolsDirectories();
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        "    api_level = 25,",
        ")");
    invalidatePackages();
    reporter.removeHandler(failFastHandler);

    try {
      getTarget("@androidsdk//:files");
      fail("android_sdk_repository should have failed due to missing SDK api level.");
    } catch (RepositoryFetchException e) {
      assertThat(e.getMessage())
          .contains(
              "Android SDK api level 25 was requested but it is not installed in the Android SDK "
                  + "at /sdk. The api levels found were [24]. Please choose an available api level "
                  + "or install api level 25 from the Android SDK Manager.");
    }
  }

  // Regression test for https://github.com/bazelbuild/bazel/issues/2739.
  @Test
  public void testFilesInSystemImagesDirectories() throws Exception {
    scratchPlatformsDirectories(24);
    scratchBuildToolsDirectories();
    scratch.file("/sdk/system-images/.DS_Store");
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();

    assertThat(getConfiguredTarget("@androidsdk//:sdk")).isNotNull();
  }

  @Test
  public void testMissingPlatformsDirectory() throws Exception {
    scratchBuildToolsDirectories();
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();
    reporter.removeHandler(failFastHandler);

    try {
      getTarget("@androidsdk//:files");
      fail("android_sdk_repository should have failed due to missing SDK platforms dir.");
    } catch (RepositoryFetchException e) {
      assertThat(e.getMessage())
          .contains("Expected directory at /sdk/platforms but it is not a directory or it does "
              + "not exist.");
    }
  }

  @Test
  public void testMissingBuildToolsDirectory() throws Exception {
    scratchPlatformsDirectories(24);
    String bazelToolsWorkspace = scratch.dir("bazel_tools_workspace").getPathString();
    FileSystemUtils.appendIsoLatin1(
        scratch.resolve("WORKSPACE"),
        "local_repository(name = 'bazel_tools', path = '" + bazelToolsWorkspace + "')",
        "android_sdk_repository(",
        "    name = 'androidsdk',",
        "    path = '/sdk',",
        ")");
    invalidatePackages();
    reporter.removeHandler(failFastHandler);

    try {
      getTarget("@androidsdk//:files");
      fail("android_sdk_repository should have failed due to missing SDK build tools dir.");
    } catch (RepositoryFetchException e) {
      assertThat(e.getMessage())
          .contains("Expected directory at /sdk/build-tools but it is not a directory or it does "
              + "not exist.");
    }
  }

  @Test
  public void testAndroidRevision() {
    assertThat(AndroidRevision.parse("2.0.0")).isGreaterThan(AndroidRevision.parse("1.0.0"));
    assertThat(AndroidRevision.parse("12.0.0")).isGreaterThan(AndroidRevision.parse("11.0.0"));
    assertThat(AndroidRevision.parse("1.1.0")).isGreaterThan(AndroidRevision.parse("1.0.0"));
    assertThat(AndroidRevision.parse("1.0.1")).isGreaterThan(AndroidRevision.parse("1.0.0"));
    assertThat(AndroidRevision.parse("1.1.1")).isGreaterThan(AndroidRevision.parse("1.0.1"));

    assertThat(AndroidRevision.parse("1.1.0-rc1"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-rc1"));
    assertThat(AndroidRevision.parse("1.1.0-alpha1"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-rc1"));

    assertThat(AndroidRevision.parse("1.0.0")).isGreaterThan(AndroidRevision.parse("1.0.0-rc1"));
    assertThat(AndroidRevision.parse("1.0.0")).isGreaterThan(AndroidRevision.parse("1.0.0-rc2"));
    assertThat(AndroidRevision.parse("1.0.0")).isGreaterThan(AndroidRevision.parse("1.0.0-alpha1"));
    assertThat(AndroidRevision.parse("1.0.0")).isGreaterThan(AndroidRevision.parse("1.0.0-alpha2"));
    assertThat(AndroidRevision.parse("1.0.0")).isGreaterThan(AndroidRevision.parse("1.0.0-beta1"));
    assertThat(AndroidRevision.parse("1.0.0")).isGreaterThan(AndroidRevision.parse("1.0.0-beta2"));

    assertThat(AndroidRevision.parse("1.0.0-rc1"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-beta1"));
    assertThat(AndroidRevision.parse("1.0.0-beta1"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-alpha1"));
    assertThat(AndroidRevision.parse("1.0.0-beta1"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-alpha2"));

    assertThat(AndroidRevision.parse("1.0.0-rc2"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-rc1"));
    assertThat(AndroidRevision.parse("1.0.0-beta2"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-beta1"));
    assertThat(AndroidRevision.parse("1.0.0-alpha2"))
        .isGreaterThan(AndroidRevision.parse("1.0.0-alpha1"));

    assertThat(AndroidRevision.parse("1.1.0-rc1"))
        .isEquivalentAccordingToCompareTo(AndroidRevision.parse("1.1.0 rc1"));

    assertThat(AndroidRevision.parse("1.0"))
        .isEquivalentAccordingToCompareTo(AndroidRevision.parse("1.0.0"));

    assertThat(AndroidRevision.parse("2")).isGreaterThan(AndroidRevision.parse("1"));
    assertThat(AndroidRevision.parse("2.1")).isGreaterThan(AndroidRevision.parse("2"));
    assertThat(AndroidRevision.parse("2")).isGreaterThan(AndroidRevision.parse("1.0"));
    assertThat(AndroidRevision.parse("2")).isGreaterThan(AndroidRevision.parse("1"));
    assertThat(AndroidRevision.parse("1 rc1")).isGreaterThan(AndroidRevision.parse("1 beta1"));

    assertThrows(NumberFormatException.class, () -> AndroidRevision.parse("1 0 0"));
    assertThrows(NumberFormatException.class, () -> AndroidRevision.parse("1.0.0-abc1"));
    assertThrows(NumberFormatException.class, () -> AndroidRevision.parse("1.0.0-rc 1"));
  }
}
