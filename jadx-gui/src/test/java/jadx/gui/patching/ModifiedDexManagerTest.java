package jadx.gui.patching;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jadx.plugins.input.smali.SmaliUtils;

import static org.assertj.core.api.Assertions.assertThat;

public class ModifiedDexManagerTest {

	private ModifiedDexManager manager;

	@BeforeEach
	public void setUp() {
		manager = ModifiedDexManager.getInstance();
		manager.clear();
	}

	@Test
	public void testRegisterAndQueryStandaloneClass() {
		String smali = ".class public Lcom/example/MainActivity;\n.super Ljava/lang/Object;\n";
		byte[] validDex = SmaliUtils.assemble(smali);

		manager.registerModifiedClass("classes.dex", "Lcom/example/MainActivity;", validDex);

		assertThat(manager.hasModifications()).isTrue();
		assertThat(manager.getModifiedClassesCount()).isEqualTo(1);
		assertThat(manager.getModifiedDexNames()).containsExactly("classes.dex");

		List<ModifiedClass> list = manager.getModifiedClasses();
		assertThat(list).hasSize(1);
		assertThat(list.get(0).getClassType()).isEqualTo("Lcom/example/MainActivity;");
		assertThat(list.get(0).getDexName()).isEqualTo("classes.dex");
		assertThat(list.get(0).hasSourceApk()).isFalse();
	}

	@Test
	public void testRegisterAndQuerySplitBundleClass() {
		String smali1 = ".class public Lcom/example/NativeHelper;\n.super Ljava/lang/Object;\n";
		byte[] dex1 = SmaliUtils.assemble(smali1);

		String smali2 = ".class public Lcom/example/BaseActivity;\n.super Ljava/lang/Object;\n";
		byte[] dex2 = SmaliUtils.assemble(smali2);

		manager.registerModifiedClass("config.arm64_v8a.apk", "classes.dex", "Lcom/example/NativeHelper;", dex1);
		manager.registerModifiedClass("base.apk", "classes2.dex", "Lcom/example/BaseActivity;", dex2);

		assertThat(manager.getModifiedClassesCount()).isEqualTo(2);
		assertThat(manager.getModifiedSourceApkNames()).containsExactlyInAnyOrder("config.arm64_v8a.apk", "base.apk");

		List<ModifiedClass> splitClasses = manager.getModifiedClasses("config.arm64_v8a.apk");
		assertThat(splitClasses).hasSize(1);
		assertThat(splitClasses.get(0).getClassType()).isEqualTo("Lcom/example/NativeHelper;");
		assertThat(splitClasses.get(0).getSourceApkName()).isEqualTo("config.arm64_v8a.apk");

		List<ModifiedClass> baseClasses = manager.getModifiedClasses("base.apk", "classes2.dex");
		assertThat(baseClasses).hasSize(1);
		assertThat(baseClasses.get(0).getClassType()).isEqualTo("Lcom/example/BaseActivity;");
	}

	@Test
	public void testClear() {
		String smali = ".class public Lcom/example/Test;\n.super Ljava/lang/Object;\n";
		byte[] validDex = SmaliUtils.assemble(smali);

		manager.registerModifiedClass("classes.dex", "Lcom/example/Test;", validDex);
		assertThat(manager.hasModifications()).isTrue();

		manager.clear();
		assertThat(manager.hasModifications()).isFalse();
		assertThat(manager.getModifiedClassesCount()).isEqualTo(0);
	}
}
