const { withAppBuildGradle } = require("expo/config-plugins");

const CONFIG_MARKER = "// MEMEPULSE_RELEASE_SIGNING_CONFIG";
const VALIDATION_MARKER = "// MEMEPULSE_RELEASE_SIGNING_VALIDATION";

function matchingBrace(source, openingBraceIndex) {
  if (source[openingBraceIndex] !== "{") {
    throw new Error("Expected a Gradle opening brace.");
  }

  let depth = 0;
  let quote = null;
  let escaped = false;
  let lineComment = false;
  let blockComment = false;

  for (let index = openingBraceIndex; index < source.length; index += 1) {
    const current = source[index];
    const next = source[index + 1];

    if (lineComment) {
      if (current === "\n") lineComment = false;
      continue;
    }
    if (blockComment) {
      if (current === "*" && next === "/") {
        blockComment = false;
        index += 1;
      }
      continue;
    }
    if (quote) {
      if (escaped) {
        escaped = false;
      } else if (current === "\\") {
        escaped = true;
      } else if (current === quote) {
        quote = null;
      }
      continue;
    }

    if (current === "/" && next === "/") {
      lineComment = true;
      index += 1;
      continue;
    }
    if (current === "/" && next === "*") {
      blockComment = true;
      index += 1;
      continue;
    }
    if (current === "'" || current === '"') {
      quote = current;
      continue;
    }
    if (current === "{") depth += 1;
    if (current === "}") {
      depth -= 1;
      if (depth === 0) return index;
    }
  }

  throw new Error("Unbalanced Gradle braces while locating a signing block.");
}

function findBlock(source, name, startIndex = 0, endIndex = source.length) {
  const pattern = new RegExp(`\\b${name}\\s*\\{`, "g");
  pattern.lastIndex = startIndex;
  const match = pattern.exec(source);
  if (!match || match.index >= endIndex) return null;

  const openingBraceIndex = match.index + match[0].lastIndexOf("{");
  const closingBraceIndex = matchingBrace(source, openingBraceIndex);
  if (closingBraceIndex >= endIndex) return null;

  return { openingBraceIndex, closingBraceIndex };
}

function addReleaseValidationTask(source) {
  if (source.includes(VALIDATION_MARKER)) return source;

  return `${source.trimEnd()}\n\n${VALIDATION_MARKER}\ntasks.register("validateMemepulseReleaseSigning") {\n    doLast {\n        def requiredEnvironment = [\n            "MEMEPULSE_KEYSTORE_PATH",\n            "MEMEPULSE_STORE_TYPE",\n            "MEMEPULSE_STORE_PASSWORD",\n            "MEMEPULSE_KEY_ALIAS",\n            "MEMEPULSE_KEY_PASSWORD"\n        ]\n        def missingEnvironment = requiredEnvironment.findAll { name ->\n            def value = System.getenv(name)\n            value == null || value.trim().isEmpty()\n        }\n        if (!missingEnvironment.isEmpty()) {\n            throw new GradleException("Missing required MemePulse release signing environment variables: " + missingEnvironment.join(", "))\n        }\n\n        def releaseKeystore = new File(System.getenv("MEMEPULSE_KEYSTORE_PATH"))\n        if (!releaseKeystore.isFile()) {\n            throw new GradleException("The configured MemePulse release keystore is unavailable in the runner temporary directory.")\n        }\n    }\n}\ntasks.matching { task ->\n    task.name == "assembleRelease" || task.name == "bundleRelease"\n}.configureEach {\n    dependsOn("validateMemepulseReleaseSigning")\n}\n`;
}

function patchGeneratedAppBuildGradle(source) {
  if (typeof source !== "string" || !source.includes("android")) {
    throw new Error("Generated Android app Gradle file is empty or invalid.");
  }

  if (source.includes(CONFIG_MARKER)) {
    const buildTypes = findBlock(source, "buildTypes");
    const releaseBuildType = buildTypes
      ? findBlock(
          source,
          "release",
          buildTypes.openingBraceIndex + 1,
          buildTypes.closingBraceIndex,
        )
      : null;
    if (
      !releaseBuildType ||
      !source
        .slice(
          releaseBuildType.openingBraceIndex,
          releaseBuildType.closingBraceIndex,
        )
        .includes("signingConfig signingConfigs.release")
    ) {
      throw new Error(
        "MemePulse release signing marker exists but the release variant is not using the release signer.",
      );
    }
    return addReleaseValidationTask(source);
  }

  let patched = source;
  let signingConfigs = findBlock(patched, "signingConfigs");
  const originalBuildTypes = findBlock(patched, "buildTypes");
  if (!originalBuildTypes) {
    throw new Error(
      "Could not locate the generated Android buildTypes block; refusing to guess at release signing.",
    );
  }

  if (signingConfigs) {
    const existingReleaseSigner = findBlock(
      patched,
      "release",
      signingConfigs.openingBraceIndex + 1,
      signingConfigs.closingBraceIndex,
    );
    if (existingReleaseSigner) {
      throw new Error(
        "A release signing config already exists. It was left untouched; inspect and reuse it instead of replacing it.",
      );
    }

    const releaseSigner = `        ${CONFIG_MARKER}\n        release {\n            def memepulseKeystorePath = System.getenv("MEMEPULSE_KEYSTORE_PATH")\n            if (memepulseKeystorePath) {\n                storeFile file(memepulseKeystorePath)\n                storeType System.getenv("MEMEPULSE_STORE_TYPE")\n                storePassword System.getenv("MEMEPULSE_STORE_PASSWORD")\n                keyAlias System.getenv("MEMEPULSE_KEY_ALIAS")\n                keyPassword System.getenv("MEMEPULSE_KEY_PASSWORD")\n            }\n        }\n`;
    const signingConfigsLineStart =
      patched.lastIndexOf("\n", signingConfigs.closingBraceIndex) + 1;
    patched =
      patched.slice(0, signingConfigsLineStart) +
      releaseSigner +
      patched.slice(signingConfigsLineStart);
  } else {
    const lineStart =
      patched.lastIndexOf("\n", originalBuildTypes.openingBraceIndex) + 1;
    const newSigningConfigs = `    signingConfigs {\n        ${CONFIG_MARKER}\n        release {\n            def memepulseKeystorePath = System.getenv("MEMEPULSE_KEYSTORE_PATH")\n            if (memepulseKeystorePath) {\n                storeFile file(memepulseKeystorePath)\n                storeType System.getenv("MEMEPULSE_STORE_TYPE")\n                storePassword System.getenv("MEMEPULSE_STORE_PASSWORD")\n                keyAlias System.getenv("MEMEPULSE_KEY_ALIAS")\n                keyPassword System.getenv("MEMEPULSE_KEY_PASSWORD")\n            }\n        }\n    }\n\n`;
    patched =
      patched.slice(0, lineStart) +
      newSigningConfigs +
      patched.slice(lineStart);
  }

  const buildTypes = findBlock(patched, "buildTypes");
  const releaseBuildType = buildTypes
    ? findBlock(
        patched,
        "release",
        buildTypes.openingBraceIndex + 1,
        buildTypes.closingBraceIndex,
      )
    : null;
  if (!releaseBuildType) {
    throw new Error(
      "Could not locate the generated release build type; refusing to leave a debug-signed APK path.",
    );
  }

  const releaseSource = patched.slice(
    releaseBuildType.openingBraceIndex,
    releaseBuildType.closingBraceIndex,
  );
  const debugSigningLine =
    /^([ \t]*)signingConfig\s+signingConfigs\.debug\s*$/gm;
  const debugSigningMatches = [...releaseSource.matchAll(debugSigningLine)];
  if (debugSigningMatches.length === 1) {
    const updatedRelease = releaseSource.replace(
      debugSigningLine,
      "$1signingConfig signingConfigs.release",
    );
    patched =
      patched.slice(0, releaseBuildType.openingBraceIndex) +
      updatedRelease +
      patched.slice(releaseBuildType.closingBraceIndex);
  } else if (!releaseSource.includes("signingConfig signingConfigs.release")) {
    throw new Error(
      "Generated release build type did not contain the expected signingConfig; refusing to modify it unsafely.",
    );
  }

  return addReleaseValidationTask(patched);
}

function withMemepulseReleaseSigning(config) {
  return withAppBuildGradle(config, (configWithGradle) => {
    if (configWithGradle.modResults.language !== "groovy") {
      throw new Error(
        "MemePulse release signing currently requires the generated Groovy app/build.gradle file.",
      );
    }
    configWithGradle.modResults.contents = patchGeneratedAppBuildGradle(
      configWithGradle.modResults.contents,
    );
    return configWithGradle;
  });
}

module.exports = withMemepulseReleaseSigning;
module.exports.patchGeneratedAppBuildGradle = patchGeneratedAppBuildGradle;
