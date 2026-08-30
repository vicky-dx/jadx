package jadx.plugins.input.smali;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmaliUtilsTest {

	@Test
	void assembleValidSmali() {
		String smali = ""
				+ ".class public LTest;\n"
				+ ".super Ljava/lang/Object;\n"
				+ ".method public static test()I\n"
				+ "    .registers 1\n"
				+ "    const/4 v0, 0x1\n"
				+ "    return v0\n"
				+ ".end method\n";

		byte[] dexBytes = SmaliUtils.assemble(smali);
		assertThat(dexBytes).isNotNull();
		assertThat(dexBytes.length).isGreaterThan(0);
		// DEX header starts with "dex\n" (0x64, 0x65, 0x78, 0x0A)
		assertThat(new String(dexBytes, 0, 4)).isEqualTo("dex\n");
	}

	@Test
	void assembleInvalidSmaliThrowsException() {
		String invalidSmali = ""
				+ ".class public LTest;\n"
				+ ".super Ljava/lang/Object;\n"
				+ ".method public static test()I\n"
				+ "    invalid_instruction_here\n"
				+ ".end method\n";

		assertThatThrownBy(() -> SmaliUtils.assemble(invalidSmali))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Smali");
	}

	@Test
	void assembleMultiClassSmali() {
		String multiClassSmali = ""
				+ "###### Class Outer\n"
				+ ".class public LOuter;\n"
				+ ".super Ljava/lang/Object;\n"
				+ ".method public static a()V\n"
				+ "    .registers 0\n"
				+ "    return-void\n"
				+ ".end method\n\n"
				+ "###### Class Outer$Inner\n"
				+ ".class public LOuter$Inner;\n"
				+ ".super Ljava/lang/Object;\n"
				+ ".method public static b()V\n"
				+ "    .registers 0\n"
				+ "    return-void\n"
				+ ".end method\n";

		byte[] dexBytes = SmaliUtils.assemble(multiClassSmali);
		assertThat(dexBytes).isNotNull();
		assertThat(dexBytes.length).isGreaterThan(0);
		assertThat(new String(dexBytes, 0, 4)).isEqualTo("dex\n");
	}
}
