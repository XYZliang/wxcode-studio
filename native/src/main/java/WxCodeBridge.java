import com.github.unidbg.Symbol;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.arm.Arm64Svc;
import java.util.Map;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.linux.file.SimpleFileIO;
import java.nio.file.Path;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.debugger.BreakPointCallback;
import com.github.unidbg.debugger.Debugger;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.ElfLibraryFile;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.array.IntArray;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.spi.LibraryFile;
import com.github.unidbg.virtualmodule.android.AndroidModule;
import com.github.unidbg.virtualmodule.android.JniGraphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.imageio.ImageIO;
import unicorn.Arm64Const;

/** Pinned offline WX_CODE JNI bridge. Current WeChat routing requires separate validation. */
public final class WxCodeBridge {
    private static final int CAPACITY = 4 * 1024 * 1024;
    private static final String LIBRARY_SHA256 =
            "cc7612519e68986e5cab185be6ec07946aebc968f4abe2509a5fe6127048b287";

    public static void main(String[] args) throws Exception {
        boolean encode = args.length == 7 && "encode".equals(args[1]);
        boolean decode = args.length == 3 && "decode".equals(args[1]);
        if (args.length != 1 && !encode && !decode) {
            throw new IllegalArgumentException("Usage: WxCodeBridge <library-directory> "
                    + "[encode <payload> <format:18> <minEc:0..3> <version:0..3> <output>"
                    + " | decode <image>]");
        }
        final File directory = new File(args[0]).getCanonicalFile();
        File library = new File(directory, "libwechatQrMod.so");
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library.toPath()))) {
            hash.append(String.format("%02x", b & 255));
        }
        if (!LIBRARY_SHA256.equals(hash.toString())) {
            throw new IllegalArgumentException("Unrecognized native library; refusing version-specific offsets");
        }
        final boolean withModels = "1".equals(System.getenv("QBAR_WITH_MODELS"));
        final Path models = directory.toPath().getParent().resolve("models").normalize();
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("local.wxcode").build();
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23) {
                @Override
                public LibraryFile resolveLibrary(Emulator<?> instance, String name) {
                    File candidate = new File(directory, name);
                    return candidate.isFile() ? new ElfLibraryFile(candidate, true)
                            : super.resolveLibrary(instance, name);
                }
            });
            if (withModels) {
                emulator.getSyscallHandler().addIOResolver((instance, pathname, oflags) -> {
                    String cpu = cpuFile(pathname);
                    if (cpu != null) {
                        return FileResult.success(new ByteArrayFileIO(oflags, pathname,
                                cpu.getBytes(StandardCharsets.US_ASCII)));
                    }
                    String prefix = "/qbar-models/";
                    if (!pathname.startsWith(prefix)) {
                        return null;
                    }
                    Path candidate = models.resolve(pathname.substring(prefix.length())).normalize();
                    if (!candidate.startsWith(models) || !Files.isRegularFile(candidate)) {
                        return FileResult.failed(2);
                    }
                    return FileResult.success(new SimpleFileIO(oflags, candidate.toFile(), pathname));
                });
            }
            VM vm = emulator.createDalvikVM();
            vm.setJni(new AbstractJni() {
                @Override
                public DvmObject<?> getObjectField(BaseVM machine, DvmObject<?> object, String signature) {
                    if (signature.startsWith("com/tencent/qbar/QbarNative$QbarAiModelParam->")) {
                        return new StringObject(machine, withModels ? modelField(signature) : "");
                    }
                    return super.getObjectField(machine, object, signature);
                }
                @Override
                public boolean getBooleanField(BaseVM machine, DvmObject<?> object, String signature) {
                    if (signature.startsWith("com/tencent/qbar/QbarNative$QbarAiModelParam->")) {
                        return withModels;
                    }
                    return super.getBooleanField(machine, object, signature);
                }
            });
            vm.setVerbose(false);
            new AndroidModule(emulator, vm) {
                @Override
                protected void onInitialize(Emulator<?> instance, VM machine,
                                            Map<String, UnidbgPointer> symbols) {
                    super.onInitialize(instance, machine, symbols);
                    symbols.put("ANativeWindow_setBuffersGeometry", instance.getSvcMemory().registerSvc(
                            new Arm64Svc("unsupportedWindowGeometry") {
                                @Override
                                public long handle(Emulator<?> ignored) {
                                    return -1;
                                }
                            }));
                }
            }.register(memory);
            new JniGraphics(emulator, vm).register(memory);
            DalvikModule loaded = vm.loadLibrary(library, false);
            loaded.callJNI_OnLoad(emulator);
            if (withModels) {
                Symbol getcpu = memory.findModule("libc.so").findSymbolByName("syscall");
                if (getcpu == null) {
                    throw new IllegalStateException("Missing emulator syscall entry");
                }
                emulator.attach().addBreakPoint(getcpu.getAddress(), (instance, address) -> {
                    if (instance.getBackend().reg_read(Arm64Const.UC_ARM64_REG_X0).longValue() == 168) {
                        for (int register : new int[] {Arm64Const.UC_ARM64_REG_X1, Arm64Const.UC_ARM64_REG_X2}) {
                            long pointer = instance.getBackend().reg_read(register).longValue();
                            if (pointer != 0) {
                                UnidbgPointer.pointer(instance, pointer).setInt(0, 0);
                            }
                        }
                        instance.getBackend().reg_write(Arm64Const.UC_ARM64_REG_X0, 0);
                        instance.getBackend().reg_write(Arm64Const.UC_ARM64_REG_PC,
                                instance.getBackend().reg_read(Arm64Const.UC_ARM64_REG_LR));
                    }
                    return true;
                });
            }
            DvmClass qbar = vm.resolveClass("com/tencent/qbar/QbarNative");
            DvmObject<?> version = qbar.callStaticJniMethodObject(emulator,
                    "GetVersion()Ljava/lang/String;");
            if (version == null) {
                throw new IllegalStateException("Native version call returned null");
            }
            System.out.println("QBAR_VERSION=" + version.getValue());
            if (encode) {
                encode(emulator, vm, qbar, loaded.getModule(), args);
            } else if (decode) {
                decode(emulator, vm, qbar, new File(args[2]));
            }
        } finally {
            emulator.close();
        }
    }

    private static void encode(AndroidEmulator emulator, VM vm, DvmClass qbar,
                               Module module, String[] args) throws Exception {
        int format = Integer.parseInt(args[3]);
        int level = Integer.parseInt(args[4]);
        int version = Integer.parseInt(args[5]);
        if (format != 18 || level < 0 || level > 3 || version < 0 || version > 3
                || args[2].getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("Unsupported format/level/version/payload length");
        }
        final byte[][] captured = new byte[1][];
        Debugger debugger = emulator.attach();
        if (format == 18) {
            debugger.addBreakPoint(module.base + 0x78d40, new BreakPointCallback() {
                @Override
                public boolean onHit(Emulator<?> instance, long address) {
                    long options = instance.getBackend().reg_read(Arm64Const.UC_ARM64_REG_X20).longValue();
                    UnidbgPointer.pointer(instance, options).setByte(0x90, (byte) 1);
                    return true;
                }
            });
        }
        debugger.addBreakPoint(module.base + 0x5bf58, new BreakPointCallback() {
            @Override
            public boolean onHit(Emulator<?> instance, long address) {
                long sp = instance.getBackend().reg_read(Arm64Const.UC_ARM64_REG_SP).longValue();
                UnidbgPointer stack = UnidbgPointer.pointer(instance, sp);
                long begin = stack.getLong(0x48);
                long end = stack.getLong(0x50);
                if (begin != 0 && end > begin && end - begin <= CAPACITY) {
                    captured[0] = instance.getBackend().mem_read(begin, (int) (end - begin));
                }
                return true;
            }
        });
        ByteArray output = new ByteArray(vm, new byte[CAPACITY]);
        IntArray dimensions = new IntArray(vm, new int[8]);
        int result = qbar.callStaticJniMethodInt(emulator,
                "Encode([B[ILjava/lang/String;IILjava/lang/String;I)I",
                output, dimensions, new StringObject(vm, args[2]), format, level,
                new StringObject(vm, "UTF-8"), version);
        System.out.println("NATIVE_RETURN=" + result);
        if (result != 0 || captured[0] == null) {
            throw new IllegalStateException("Encoder did not return a nonempty output vector");
        }
        if (format == 18 && !new String(captured[0], StandardCharsets.UTF_8).contains("<svg")) {
            throw new IllegalStateException("WX output was not an SVG document");
        }
        Files.write(Paths.get(args[6]), captured[0]);
        System.out.println("NATIVE_OUTPUT_BYTES=" + captured[0].length);
        System.out.println("NATIVE_OUTPUT=" + Paths.get(args[6]).toAbsolutePath());
        System.out.println("NOTICE=Native encoding does not prove WeChat recognition or URL routing.");
    }

    private static void decode(AndroidEmulator emulator, VM vm, DvmClass qbar,
                               File source) throws Exception {
        BufferedImage image = ImageIO.read(source);
        if (image == null) {
            throw new IllegalArgumentException("Unsupported image: " + source);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if ((long) width * height > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Probe image is too large");
        }
        byte[] gray = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                gray[y * width + x] = (byte) ((((rgb >>> 16) & 255) * 299
                        + ((rgb >>> 8) & 255) * 587 + (rgb & 255) * 114 + 500) / 1000);
            }
        }
        int id = qbar.callStaticJniMethodInt(emulator,
                "Init(IZZZZIILjava/lang/String;Ljava/lang/String;"
                        + "Lcom/tencent/qbar/QbarNative$QbarAiModelParam;)I",
                1, true, true, true, true, 1, 0,
                new StringObject(vm, "ANY"), new StringObject(vm, "UTF-8"),
                vm.resolveClass("com/tencent/qbar/QbarNative$QbarAiModelParam").newObject(null));
        System.out.println("SCANNER_ID=" + id);
        if (id < 0) {
            throw new IllegalStateException("Native scanner initialization failed");
        }
        try {
            int readers = qbar.callStaticJniMethodInt(emulator, "SetReaders([III)I",
                    new IntArray(vm, new int[] {3, 2}), 2, id);
            System.out.println("SET_READERS_RETURN=" + readers);
            int scanned = qbar.callStaticJniMethodInt(emulator, "ScanImage([BIII)I",
                    new ByteArray(vm, gray), width, height, id);
            System.out.println("SCAN_RETURN=" + scanned);
            ByteArray type = new ByteArray(vm, new byte[128]);
            ByteArray payload = new ByteArray(vm, new byte[65536]);
            ByteArray charset = new ByteArray(vm, new byte[128]);
            IntArray lengths = new IntArray(vm, new int[3]);
            int found = qbar.callStaticJniMethodInt(emulator, "GetOneResult([B[B[B[II)I",
                    type, payload, charset, lengths, id);
            System.out.println("GET_RESULT_RETURN=" + found);
            System.out.println("RESULT_LENGTHS=" + Arrays.toString(lengths.getValue()));
            if (found != 1) {
                throw new IllegalStateException("Native scanner did not decode an image");
            }
            byte[] decoded = boundedPrefix(payload.getValue(), lengths.getValue()[1]);
            System.out.println("DECODED_TYPE=" + new String(
                    boundedPrefix(type.getValue(), lengths.getValue()[0]), StandardCharsets.UTF_8));
            System.out.println("DECODED_CHARSET=" + new String(
                    boundedPrefix(charset.getValue(), lengths.getValue()[2]), StandardCharsets.UTF_8));
            System.out.println("PAYLOAD_BASE64=" + Base64.getEncoder().encodeToString(decoded));
        } finally {
            qbar.callStaticJniMethodInt(emulator, "Release(I)I", id);
        }
    }

    private static byte[] boundedPrefix(byte[] data, int length) {
        if (length < 0 || length > data.length) {
            throw new IllegalStateException("Native result exceeds output capacity");
        }
        return Arrays.copyOf(data, length);
    }

    private static String modelField(String signature) {
        String name = signature.substring(signature.indexOf("->") + 2, signature.lastIndexOf(':'));
        switch (name) {
            case "detectModelVersion": return "V1.5.0.26";
            case "superResolutionModelVersion": return "V1.1.0.26";
            case "qbar_segmentation_model_path_":
                return "/qbar-models/QBarModels/V1.1.0.26/qbar_seg.xnet";
            case "detect_model_bin_path_":
                return "/qbar-models/QBarModels/V1.5.0.26/qbar_detect.xnet";
            case "superresolution_model_bin_path_":
                return "/qbar-models/QBarModels/V1.1.0.26/qbar_sr.xnet";
            case "superresolution_model_param_path_": return "/qbar-models/srnet.param";
            default: return "";
        }
    }

    private static String cpuFile(String path) {
        switch (path) {
            case "/sys/devices/system/cpu/possible":
            case "/sys/devices/system/cpu/present":
            case "/sys/devices/system/cpu/online":
            case "/sys/devices/system/cpu/kernel_max": return "0\n";
            case "/proc/cpuinfo":
                return "processor\t: 0\nBogoMIPS\t: 38.40\n"
                        + "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"
                        + "CPU implementer\t: 0x41\nCPU architecture: 8\n"
                        + "CPU variant\t: 0x0\nCPU part\t: 0xd03\nCPU revision\t: 4\n"
                        + "\nHardware\t: Generic ARMv8\n";
            case "/sys/devices/system/cpu/cpu0/topology/core_id":
            case "/sys/devices/system/cpu/cpu0/topology/physical_package_id":
            case "/sys/devices/system/cpu/cpu0/topology/core_siblings_list":
            case "/sys/devices/system/cpu/cpu0/topology/thread_siblings_list": return "0\n";
            case "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq": return "300000\n";
            case "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq": return "1200000\n";
            default: return null;
        }
    }
}
