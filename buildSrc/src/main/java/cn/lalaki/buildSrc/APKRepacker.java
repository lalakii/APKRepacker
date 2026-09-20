package cn.lalaki.buildSrc;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.SigningConfig;
import com.android.build.api.variant.VariantOutput;
import com.google.common.base.CaseFormat;
import com.google.common.io.ByteStreams;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.file.Directory;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.NonNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;

/**
 * @author lalaki i@lalaki.cn
 * @version 1.2.2
 */
@SuppressWarnings("unused")
public class APKRepacker implements Plugin<Project> {

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void apply(@NonNull Project project) {
        final RepackExt repackExt = project.getExtensions().create("repackageConfig", RepackExt.class);
        final ApplicationAndroidComponentsExtension androidComponents = project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        final String buildTool = getSdkBuildTool(project.getExtensions().getByType(AndroidComponentsExtension.class).getSdkComponents().getSdkDirectory().get().getAsFile().getAbsolutePath());
        androidComponents.onVariants(androidComponents.selector(), variant -> {
            final String variantName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, variant.getName());
            final TaskProvider<Task> repackProvider = project.getTasks().register("repackage" + variantName);
            final Provider<Directory> artifactDir = variant.getArtifacts().get(SingleArtifact.APK.INSTANCE);
            final SigningConfig configs = variant.getSigningConfig();
            final Property<Boolean> legacySo = variant.getPackaging().getJniLibs().getUseLegacyPackaging();
            final ArrayList<Property<String>> outputFileNames = new ArrayList<>();
            for (VariantOutput output : variant.getOutputs()) {
                outputFileNames.add(output.getOutputFileName());
            }
            final int targetSdkVersion = variant.getTargetSdk().getApiLevel();
            final int minSdkVersion = variant.getMinSdk().getApiLevel();
            repackProvider.configure(task -> {
                task.doLast(repacking -> {
                    final String outputDir = artifactDir.get().getAsFile().getAbsolutePath();
                    final boolean autoSign = repackExt.getAutoSign().getOrElse(true);
                    final String storeFile = repackExt.getStoreFile().getOrElse("");
                    final String signerName = repackExt.getSignerName().getOrElse("_");
                    final String keyAlias = repackExt.getKeyAlias().getOrElse("");
                    final String ksPass = repackExt.getKsPass().getOrElse("");
                    final String keyPass = repackExt.getKeyPass().getOrElse("");
                    final boolean v1Sign = configs.getEnableV1Signing().getOrElse(true);
                    final boolean v2Sign = configs.getEnableV2Signing().getOrElse(true);
                    final boolean v3Sign = configs.getEnableV3Signing().getOrElse(false);
                    final boolean v4Sign = configs.getEnableV4Signing().getOrElse(false);
                    final boolean jniLegacy = legacySo.getOrElse(false);
                    final boolean debugKt = repackExt.getDebugKt().getOrElse(false);
                    final boolean includeMetaData = repackExt.getIncludeMetaData().getOrElse(false);
                    final boolean printOutputApk = repackExt.getPrintOutputApk().getOrElse(false);
                    final List<String> blacklist = repackExt.getBlackList().getOrElse(List.of());
                    final List<String> addList = repackExt.getAddAssetsList().getOrElse(List.of());
                    for (Property<String> outputFileName : outputFileNames) {
                        if (outputFileName.isPresent()) {
                            final String outputFile = FilenameUtils.concat(outputDir, outputFileName.get());
                            repacking(repacking.getLogger(), buildTool, outputFile, minSdkVersion, targetSdkVersion, autoSign, storeFile, signerName, keyAlias, ksPass, keyPass, v1Sign, v2Sign, v3Sign, v4Sign, debugKt, jniLegacy, includeMetaData, printOutputApk, blacklist, addList);
                        }
                    }
                });
                task.onlyIf(_ -> !repackExt.getDisabled().getOrElse(false));
                final Property<String> afterTask = repackExt.getDoAfterTask();
                if (afterTask.isPresent()) {
                    final String afterTaskName = afterTask.getOrElse("");
                    if (!afterTaskName.isBlank()) {
                        try {
                            task.finalizedBy(project.getTasks().named(afterTaskName));
                        } catch (UnknownTaskException _) {
                        }
                    }
                }
            });
            final String assembleTaskName = "assemble" + variantName;
            project.getTasks().whenTaskAdded(task -> {
                if (task.getName().equalsIgnoreCase(assembleTaskName)) {
                    task.finalizedBy(repackProvider);
                }
            });
        });
    }

    private static void repacking(Logger logger, String buildTool, String outputFile, int minSdkVersion, int targetSdkVersion, boolean autoSign, String storeFile, String signerName, String keyAlias, String ksPass, String keyPass, boolean v1Sign, boolean v2Sign, boolean v3Sign, boolean v4Sign, boolean debugKt, boolean jniLegacy, boolean includeMetaData, boolean printOutputApk, List<String> blacklist, List<String> addList) {
        try {
            File output = new File(outputFile);
            if (output.isFile()) {
                final ProcessBuilder builder = new ProcessBuilder();
                // repack
                final String unAlignApk = FilenameUtils.concat(output.getParent(), FilenameUtils.getBaseName(outputFile) + "-unalign.apk");
                try (FileOutputStream fos = new FileOutputStream(unAlignApk)) {
                    ZipArchiveOutputStream zos = new ZipArchiveOutputStream(new BufferedOutputStream(fos));
                    zos.setLevel(Deflater.BEST_COMPRESSION);
                    filterZip(output, jniLegacy, blacklist, addList, debugKt, includeMetaData, zos);
                }
                // zipAlign
                File alignExe = new File(FilenameUtils.concat(buildTool, "zipalign.exe"));
                if (!alignExe.isFile()) {
                    alignExe = new File(FilenameUtils.concat(buildTool, "zipalign"));
                }
                final Process alignProc = builder.command(alignExe.getAbsolutePath(), "-P", "16", "-f", "4", unAlignApk, outputFile).start();
                try {
                    alignProc.waitFor();
                } catch (InterruptedException _) {
                }
                if (printOutputApk) {
                    logger.lifecycle("{}APK has been generated at: {}", System.lineSeparator(), outputFile);
                }
                try {
                    FileUtils.forceDelete(new File(unAlignApk));
                } catch (IOException _) {
                }
                // sign
                if (autoSign) {
                    File signBat = new File(FilenameUtils.concat(buildTool, "apksigner.bat"));
                    if (!signBat.isFile()) {
                        signBat = new File(FilenameUtils.concat(buildTool, "apksigner"));
                    }
                    boolean signV2;
                    if (targetSdkVersion > 29) {
                        signV2 = true;
                    } else {
                        signV2 = v2Sign;
                    }
                    String finalSignerName;
                    if (!signerName.isBlank()) {
                        finalSignerName = signerName;
                    } else {
                        finalSignerName = "_";
                    }
                    final Process signProc = builder.command(signBat.getAbsolutePath(), "sign", "--min-sdk-version", String.valueOf(minSdkVersion), "--v1-signer-name", finalSignerName, "--v1-signing-enabled", String.valueOf(v1Sign), "--v2-signing-enabled", String.valueOf(signV2), "--v3-signing-enabled", String.valueOf(v3Sign), "--v4-signing-enabled", String.valueOf(v4Sign), "--ks", storeFile, "--ks-key-alias", keyAlias, "--ks-pass", "pass:" + ksPass, "--key-pass", "pass:" + keyPass, outputFile).start();
                    try {
                        final int code = signProc.waitFor();
                        if (code != 0) {
                            throw new RuntimeException("APK signing failed with status code: " + code);
                        }
                    } catch (InterruptedException _) {
                    }
                }
            }
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    private static void filterZip(File output, boolean jniLegacy, List<String> blacklist, List<String> addList, boolean debugKt, boolean includeMetaData, ZipArchiveOutputStream zos) throws IOException {
        try (ZipFile apk = new ZipFile(output)) {
            final Set<String> blackSet = getBlackSet(blacklist, debugKt, includeMetaData);
            Stream<FileHeader> headerStream = apk.getFileHeaders().stream();
            for (String rule : blackSet) {
                if (rule.contains("*")) {
                    headerStream = headerStream.filter(it -> !FilenameUtils.wildcardMatch(it.getFileName(), rule));
                } else {
                    headerStream = headerStream.filter(it -> !it.getFileName().endsWith(rule));
                }
            }
            copyToNewZip(headerStream, apk, jniLegacy, zos);
            if (addList != null && !addList.isEmpty()) {
                for (String it : addList) {
                    addFileToZip(new File(it), zos);
                }
            }
            zos.close();
        }
    }

    private static Set<String> getBlackSet(List<String> blacklist, boolean debugKt, boolean includeMetaData) {
        Set<String> blackSet;
        if (blacklist != null && !blacklist.isEmpty()) {
            blackSet = new HashSet<>(blacklist);
        } else {
            blackSet = new HashSet<>();
        }
        if (!debugKt) {
            blackSet.add("DebugProbesKt.bin");
        }
        if (!includeMetaData) {
            blackSet.add("version-control-info.textproto");
            blackSet.add("app-metadata.properties");
        }
        return blackSet;
    }

    @SuppressWarnings("TryFinallyCanBeTryWithResources")
    private static void addFileToZip(File f, ZipArchiveOutputStream zos) {
        if (f.isFile()) {
            final ZipArchiveEntry entry = new ZipArchiveEntry("assets/" + f.getName());
            entry.setMethod(ZipArchiveEntry.DEFLATED);
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(f);
                zos.putArchiveEntry(entry);
                ByteStreams.copy(fis, zos);
                zos.closeArchiveEntry();
            } catch (IOException _) {
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException _) {
                    }
                }
            }
        }
    }

    private static void copyToNewZip(Stream<FileHeader> headerStream, ZipFile apk, boolean jniLegacy, ZipArchiveOutputStream zos) {
        headerStream.forEach(item -> {
            final String fileName = item.getFileName();
            final ZipArchiveEntry entry = new ZipArchiveEntry(fileName);
            entry.setCrc(item.getCrc());
            if (fileName.equalsIgnoreCase("resources.arsc") || (!jniLegacy && fileName.endsWith(".so"))) {
                entry.setMethod(ZipArchiveEntry.STORED);
                entry.setSize(item.getCompressedSize());
            } else if (fileName.endsWith(".dex")) {
                entry.setMethod(ZipArchiveEntry.DEFLATED);
                entry.setUnixMode(32768 | 420);
            } else {
                entry.setMethod(ZipArchiveEntry.DEFLATED);
            }
            try {
                zos.putArchiveEntry(entry);
                ByteStreams.copy(apk.getInputStream(item), zos);
                zos.closeArchiveEntry();
            } catch (IOException _) {
            }
        });
    }

    private static String getSdkBuildTool(String sdkDir) {
        final File buildToolsDir = new File(FilenameUtils.concat(sdkDir, "build-tools"));
        if (FileUtils.isDirectory(buildToolsDir)) {
            final File[] list = buildToolsDir.listFiles();
            if (list != null) {
                try (Stream<File> stream = Arrays.stream(list)) {
                    final Iterator<File> it = stream.sorted(Comparator.reverseOrder()).iterator();
                    if (it.hasNext()) {
                        return it.next().getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    public abstract static class RepackExt {
        @Optional
        @Input
        public abstract Property<Boolean> getAutoSign();

        @Optional
        @Input
        public abstract Property<String> getStoreFile();

        @Optional
        @Input
        public abstract Property<String> getKeyAlias();

        @Optional
        @Input
        public abstract Property<String> getKsPass();

        @Optional
        @Input
        public abstract Property<String> getKeyPass();

        @Optional
        @Input
        public abstract Property<String> getSignerName();

        @Optional
        @Input
        public abstract Property<Boolean> getIncludeMetaData();

        @Optional
        @Input
        public abstract Property<Boolean> getDebugKt();

        @Optional
        @Input
        public abstract ListProperty<String> getBlackList();

        @Optional
        @Input
        public abstract ListProperty<String> getAddAssetsList();

        @Optional
        @Input
        public abstract Property<Boolean> getDisabled();

        @Optional
        @Input
        public abstract Property<Boolean> getPrintOutputApk();

        @Optional
        @Input
        public abstract Property<String> getDoAfterTask();

        public RepackExt(ObjectFactory objectFactory) {
        }
    }
}