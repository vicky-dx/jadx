package jadx.plugins.input.smali;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.android.tools.smali.dexlib2.iface.ClassDef;

import static org.assertj.core.api.Assertions.assertThat;

public class DexPatcherTest {

	@Test
	public void testPatchSingleClassInMultiClassDex() {
		String smaliOriginal = ""
				+ ".class public Lcom/example/ClassA;\n"
				+ ".super Ljava/lang/Object;\n"
				+ ".method public static test()I\n"
				+ "    .registers 1\n"
				+ "    const/4 v0, 0x1\n"
				+ "    return v0\n"
				+ ".end method\n"
				+ "\n"
				+ ".class public Lcom/example/ClassB;\n"
				+ ".super Ljava/lang/Object;\n"
				+ ".method public static hello()Ljava/lang/String;\n"
				+ "    .registers 1\n"
				+ "    const-string v0, \"world\"\n"
				+ "    return-object v0\n"
				+ ".end method\n";

		byte[] originalDex = SmaliUtils.assemble(smaliOriginal);
		assertThat(originalDex).isNotNull();

		List<? extends ClassDef> originalClasses = DexPatcher.readClasses(originalDex);
		assertThat(originalClasses).hasSize(2);

		// Modified ClassA: return 0x99 instead of 0x1
		String smaliModifiedA = ""
				+ ".class public Lcom/example/ClassA;\n"
				+ ".super Ljava/lang/Object;\n"
				+ ".method public static test()I\n"
				+ "    .registers 1\n"
				+ "    const/16 v0, 0x99\n"
				+ "    return v0\n"
				+ ".end method\n";

		byte[] modifiedDexA = SmaliUtils.assemble(smaliModifiedA);
		List<? extends ClassDef> modifiedClassesA = DexPatcher.readClasses(modifiedDexA);
		assertThat(modifiedClassesA).hasSize(1);

		// Patch originalDex with modified ClassA
		byte[] patchedDex = DexPatcher.patchDex(originalDex, modifiedClassesA);
		assertThat(patchedDex).isNotNull();

		List<? extends ClassDef> patchedClasses = DexPatcher.readClasses(patchedDex);
		assertThat(patchedClasses).hasSize(2);

		Set<String> classTypes = new HashSet<>();
		for (ClassDef cd : patchedClasses) {
			classTypes.add(cd.getType());
		}
		assertThat(classTypes).containsExactlyInAnyOrder("Lcom/example/ClassA;", "Lcom/example/ClassB;");
	}

	@Test
	public void testApkPatcherReplacesDexAndPreservesResources(@TempDir Path tempDir) throws IOException {
		Path mockApk = tempDir.resolve("sample.apk");
		Path patchedApk = tempDir.resolve("sample_patched.apk");

		byte[] origDex = SmaliUtils.assemble(".class public Lcom/test/Main;\n.super Ljava/lang/Object;\n");

		// Build a mock APK with classes.dex, AndroidManifest.xml, and META-INF signature
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(mockApk))) {
			zos.putNextEntry(new ZipEntry("classes.dex"));
			zos.write(origDex);
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
			zos.write("<manifest/>".getBytes());
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("res/layout/main.xml"));
			zos.write("<LinearLayout/>".getBytes());
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("META-INF/CERT.RSA"));
			zos.write(new byte[]{1, 2, 3});
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
			zos.write("Manifest-Version: 1.0\n".getBytes());
			zos.closeEntry();
		}

		// Patch classes.dex with a modified class
		byte[] newDex = SmaliUtils.assemble(".class public Lcom/test/Main;\n.super Ljava/lang/Object;\n.field public static ID:I\n");
		Map<String, byte[]> patchMap = new HashMap<>();
		patchMap.put("classes.dex", newDex);

		ApkPatcher.patchApk(mockApk, patchedApk, patchMap);

		assertThat(Files.exists(patchedApk)).isTrue();

		Set<String> entriesInPatchedApk = new HashSet<>();
		byte[] extractedDex = null;
		byte[] extractedManifest = null;

		try (ZipFile zip = new ZipFile(patchedApk.toFile())) {
			Enumeration<? extends ZipEntry> en = zip.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				entriesInPatchedApk.add(e.getName());
				if (e.getName().equals("classes.dex")) {
					extractedDex = zip.getInputStream(e).readAllBytes();
				}
				if (e.getName().equals("AndroidManifest.xml")) {
					extractedManifest = zip.getInputStream(e).readAllBytes();
				}
			}
		}

		// Verify META-INF signatures were stripped
		assertThat(entriesInPatchedApk).contains("classes.dex", "AndroidManifest.xml", "res/layout/main.xml");
		assertThat(entriesInPatchedApk).doesNotContain("META-INF/CERT.RSA", "META-INF/MANIFEST.MF");

		// Verify DEX was replaced
		assertThat(extractedDex).isEqualTo(newDex);

		// Verify Manifest was preserved verbatim
		assertThat(extractedManifest).isEqualTo("<manifest/>".getBytes());
	}

	@Test
	public void testMultiDexApkPatchingWithModifiedClasses(@TempDir Path tempDir) throws IOException {
		Path mockApk = tempDir.resolve("multidex_sample.apk");
		Path patchedApk = tempDir.resolve("multidex_patched.apk");

		byte[] origDex1 = SmaliUtils.assemble(""
				+ ".class public Lcom/example/ClassA;\n"
				+ ".super Ljava/lang/Object;\n"
				+ "\n"
				+ ".class public Lcom/example/ClassB;\n"
				+ ".super Ljava/lang/Object;\n");

		byte[] origDex2 = SmaliUtils.assemble(""
				+ ".class public Lcom/example/ClassC;\n"
				+ ".super Ljava/lang/Object;\n"
				+ "\n"
				+ ".class public Lcom/example/ClassD;\n"
				+ ".super Ljava/lang/Object;\n");

		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(mockApk))) {
			zos.putNextEntry(new ZipEntry("classes.dex"));
			zos.write(origDex1);
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("classes2.dex"));
			zos.write(origDex2);
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("assets/config.json"));
			zos.write("{\"key\":\"value\"}".getBytes());
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("META-INF/CERT.SF"));
			zos.write(new byte[]{4, 5, 6});
			zos.closeEntry();
		}

		// Modify ClassA in classes.dex and ClassC in classes2.dex
		byte[] modDexA = SmaliUtils.assemble(".class public Lcom/example/ClassA;\n.super Ljava/lang/Object;\n.field public static MOD_A:I\n");
		byte[] modDexC = SmaliUtils.assemble(".class public Lcom/example/ClassC;\n.super Ljava/lang/Object;\n.field public static MOD_C:I\n");

		Map<String, List<? extends ClassDef>> modMap = new HashMap<>();
		modMap.put("classes.dex", DexPatcher.readClasses(modDexA));
		modMap.put("classes2.dex", DexPatcher.readClasses(modDexC));

		ApkPatcher.patchApkWithClasses(mockApk, patchedApk, modMap);

		assertThat(Files.exists(patchedApk)).isTrue();

		try (ZipFile zip = new ZipFile(patchedApk.toFile())) {
			assertThat(zip.getEntry("META-INF/CERT.SF")).isNull();
			assertThat(zip.getEntry("assets/config.json")).isNotNull();

			// Check classes.dex contains ClassA (modified) and ClassB (preserved)
			byte[] patchedDex1Bytes = zip.getInputStream(zip.getEntry("classes.dex")).readAllBytes();
			List<? extends ClassDef> classesInDex1 = DexPatcher.readClasses(patchedDex1Bytes);
			assertThat(classesInDex1).hasSize(2);
			Set<String> dex1Types = new HashSet<>();
			for (ClassDef cd : classesInDex1) {
				dex1Types.add(cd.getType());
			}
			assertThat(dex1Types).containsExactlyInAnyOrder("Lcom/example/ClassA;", "Lcom/example/ClassB;");

			// Check classes2.dex contains ClassC (modified) and ClassD (preserved)
			byte[] patchedDex2Bytes = zip.getInputStream(zip.getEntry("classes2.dex")).readAllBytes();
			List<? extends ClassDef> classesInDex2 = DexPatcher.readClasses(patchedDex2Bytes);
			assertThat(classesInDex2).hasSize(2);
			Set<String> dex2Types = new HashSet<>();
			for (ClassDef cd : classesInDex2) {
				dex2Types.add(cd.getType());
			}
			assertThat(dex2Types).containsExactlyInAnyOrder("Lcom/example/ClassC;", "Lcom/example/ClassD;");
		}
	}
}
