import { describe, expect, it } from "vitest";

const { patchGeneratedAppBuildGradle } =
  require("../scripts/with-memepulse-release-signing.js") as {
    patchGeneratedAppBuildGradle: (source: string) => string;
  };

const generatedGradle = `apply plugin: "com.android.application"
android {
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
        release {
            signingConfig signingConfigs.debug
            minifyEnabled false
        }
    }
}
`;

describe("MemePulse Android release signing plugin", () => {
  it("uses the secret-backed release signer and leaves debug signing unchanged", () => {
    const patched = patchGeneratedAppBuildGradle(generatedGradle);
    const buildTypesStart = patched.indexOf("buildTypes {");
    const releaseStart = patched.indexOf("release {", buildTypesStart);
    const packagingOrNextBlock = patched.indexOf("\n        }", releaseStart);
    const releaseBuildType = patched.slice(releaseStart, packagingOrNextBlock);

    expect(patched).toContain("storeFile file(memepulseKeystorePath)");
    expect(patched).toContain('System.getenv("MEMEPULSE_STORE_PASSWORD")');
    expect(patched).toContain('System.getenv("MEMEPULSE_KEY_ALIAS")');
    expect(patched).toContain('System.getenv("MEMEPULSE_KEY_PASSWORD")');
    expect(patched).toContain(
      'storeType System.getenv("MEMEPULSE_STORE_TYPE")',
    );
    expect(releaseBuildType).toContain("signingConfig signingConfigs.release");
    expect(releaseBuildType).not.toContain(
      "signingConfig signingConfigs.debug",
    );
    expect(patched).toContain("signingConfig signingConfigs.debug");
    expect(patched).toContain("        }\n    }\n    buildTypes {");
    expect(patched).toContain(
      'tasks.register("validateMemepulseReleaseSigning")',
    );
    expect(patched).toContain(
      'task.name == "assembleRelease" || task.name == "bundleRelease"',
    );
  });

  it("is idempotent when Expo prebuild applies the plugin more than once", () => {
    const once = patchGeneratedAppBuildGradle(generatedGradle);
    expect(patchGeneratedAppBuildGradle(once)).toBe(once);
  });

  it("does not overwrite an existing release keystore configuration", () => {
    const existingReleaseConfig = generatedGradle.replace(
      "    }\n    buildTypes {",
      `        release {\n            storeFile file("existing-production-key.jks")\n        }\n    }\n    buildTypes {`,
    );

    expect(() => patchGeneratedAppBuildGradle(existingReleaseConfig)).toThrow(
      /already exists/i,
    );
  });

  it("fails safely when the generated Gradle template no longer has the expected release block", () => {
    expect(() =>
      patchGeneratedAppBuildGradle("android { buildTypes { debug {} } }"),
    ).toThrow(/release build type/i);
  });
});
