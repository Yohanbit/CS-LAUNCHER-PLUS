package net.kdt.pojavlaunch;

import android.os.Build;
import android.os.Process;
import java.util.Locale;

/**
 * This class aims at providing a simple and easy way to deal with the device architecture.
 */
public class Architecture {
	public static final int UNSUPPORTED_ARCH = -1;
	public static final int ARCH_ARM64 = 0x1;
	public static final int ARCH_ARM = 0x2;
	public static final int ARCH_X86 = 0x4;
	public static final int ARCH_X86_64 = 0x8;

	/* On both 32-bit ARM and x86, the top 1GB is reserved for kernel use. */
	public static final long ADDRESS_SPACE_LIMIT_32_BIT = 0xbfffffffL;
	/*
	 * Technically, this is supposed to be 48 bits on x86_64, but nobody's allocating
	 * 524288 terabytes of RAM on Pojav any time soon.
	 */
	public static final long ADDRESS_SPACE_LIMIT_64_BIT = 0x7fffffffffL;

	/**
	 * Get the highest byte accessible within the process's address space.
	 * @return the highest byte accessible within the process's address space.
	 */
	public static long getAddressSpaceLimit() {
		return is64BitProcess() ? ADDRESS_SPACE_LIMIT_64_BIT : ADDRESS_SPACE_LIMIT_32_BIT;
	}

	/** The address space and JNI libraries belong to this process, not to the phone's ABI list. */
	public static boolean is64BitProcess() {
		if (Build.VERSION.SDK_INT >= 23) return Process.is64Bit();
		// API 21-22 has no public Process.is64Bit(). ART's os.arch describes its running VM.
		// Unknown values fail narrow; never upgrade a 32-bit process from SUPPORTED_64_BIT_ABIS.
		int arch = archAsInt(System.getProperty("os.arch"));
		return arch == ARCH_ARM64 || arch == ARCH_X86_64;
	}

	public static boolean is32BitProcess() { return !is64BitProcess(); }

	/** Active VM instruction family + actual process width. Unknown VM ABI stays unsupported. */
	public static int getProcessArchitecture() {
		int arch = archAsInt(System.getProperty("os.arch"));
		if (arch == ARCH_ARM || arch == ARCH_ARM64)
			return is64BitProcess() ? ARCH_ARM64 : ARCH_ARM;
		if (arch == ARCH_X86 || arch == ARCH_X86_64)
			return is64BitProcess() ? ARCH_X86_64 : ARCH_X86;
		return UNSUPPORTED_ARCH;
	}

	/** Refuse an incompatible/unknown embedded JVM before changing paths or loading JNI. */
	public static void requireCompatibleRuntimeArchitecture(String runtimeAbi) {
		int processArch = getProcessArchitecture();
		if (processArch == UNSUPPORTED_ARCH || archAsInt(runtimeAbi) != processArch)
			throw new IllegalArgumentException("Java runtime ABI " + runtimeAbi
					+ " does not match running process ABI " + archAsString(processArch));
	}

	/**
	 * Tell us if the device supports 64 bits architecture
	 * @return If the device supports 64 bits architecture
	 */
	public static boolean is64BitsDevice(){
		return Build.SUPPORTED_64_BIT_ABIS.length != 0;
	}

	/**
	 * Tell us if the device supports 32 bits architecture
	 * Note, that a 64 bits device won't be reported as supporting 32 bits.
	 * @return If the device supports 32 bits architecture
	 */
	public static boolean is32BitsDevice(){
		return !is64BitsDevice();
	}

	/**
	 * Legacy selector used by runtime downloads, native extraction and Tools.DEVICE_ARCHITECTURE.
	 * Despite its historical name this must select the running process ABI, not the highest ABI
	 * the device supports: a 32-bit APK cannot dlopen a 64-bit JVM or native library.
	 *
	 * @return ARCH_ARM || ARCH_ARM64 || ARCH_X86 || ARCH_86_64
	 */
	public static int getDeviceArchitecture(){
		return getProcessArchitecture();
	}

	/**
	 * Tell is the device is based on an x86 processor.
	 * It doesn't tell if the device is 64 or 32 bits.
	 * @return Whether or not the device is x86 based.
	 */
	public static boolean isx86Device(){
		//We check the whole range of supported ABIs,
		//Since asus zenfones can place arm before their native instruction set.
		String[] ABI = is64BitsDevice() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
		int comparedArch = is64BitsDevice() ? ARCH_X86_64 : ARCH_X86;
		for (String str : ABI) {
			if (archAsInt(str) == comparedArch) return true;
		}
		return false;
	}



	/**
	 * Convert an architecture from a String to an int.
	 * @param arch The architecture as a String
	 * @return The architecture as an int, can be UNSUPPORTED_ARCH if unknown.
	 */
	public static int archAsInt(String arch){
		if (arch == null) return UNSUPPORTED_ARCH;
		arch = arch.toLowerCase(Locale.ROOT).trim().replace(" ", "");
		if(arch.contains("arm64") || arch.equals("aarch64")) return ARCH_ARM64;
		if(arch.contains("arm") || arch.equals("aarch32")) return ARCH_ARM;
		if(arch.contains("x86_64") || arch.contains("amd64")) return ARCH_X86_64;
		if(arch.contains("x86") || (arch.startsWith("i") && arch.endsWith("86"))) return ARCH_X86;
		//Shouldn't happen
		return UNSUPPORTED_ARCH;
	}

	/**
	 * Convert to a string an architecture.
	 * @param arch The architecture as an int.
	 * @return "arm64" || "arm" || "x86_64" || "x86" || "UNSUPPORTED_ARCH"
	 */
	public static String archAsString(int arch){
		if(arch == ARCH_ARM64) return "arm64";
		if(arch == ARCH_ARM) return "arm";
		if(arch == ARCH_X86_64) return "x86_64";
		if(arch == ARCH_X86) return "x86";
		return "UNSUPPORTED_ARCH";
	}
    /**
     * Convert to a string an architecture.
     * @param arch The architecture as an int.
     * @return "arm64" || "arm" || "x86_64" || "x86" || "UNSUPPORTED_ARCH"
     */
    public static String archAsStringAndroid(int arch) {
        if(arch == ARCH_ARM64) return "arm64-v8a";
        if(arch == ARCH_ARM) return "armeabi-v7a";
        if(arch == ARCH_X86_64) return "x86_64";
        if(arch == ARCH_X86) return "x86";
        return "UNSUPPORTED_ARCH";
    }

}
