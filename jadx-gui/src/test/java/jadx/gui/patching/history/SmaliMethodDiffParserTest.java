package jadx.gui.patching.history;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmaliMethodDiffParserTest {

	@Test
	void testNoChangesWhenIdentical() {
		String smali = ".class public Lcom/example/Test;\n"
				+ ".super Ljava/lang/Object;\n\n"
				+ ".method public hello()V\n"
				+ "    return-void\n"
				+ ".end method\n";

		List<SmaliMethodChange> changes = SmaliMethodDiffParser.parseChanges("Lcom/example/Test;", smali, smali);
		assertThat(changes).isEmpty();
	}

	@Test
	void testScenario2_SingleHunkParseAndRevert() {
		String baseline = ".class public Lcom/example/Auth;\n"
				+ ".super Ljava/lang/Object;\n\n"
				+ ".method public isLicensed()Z\n"
				+ "    .registers 2\n"
				+ "    const/4 v0, 0x0\n"
				+ "    return v0\n"
				+ ".end method\n";

		String current = ".class public Lcom/example/Auth;\n"
				+ ".super Ljava/lang/Object;\n\n"
				+ ".method public isLicensed()Z\n"
				+ "    .registers 2\n"
				+ "    const/4 v0, 0x1\n"
				+ "    return v0\n"
				+ ".end method\n";

		List<SmaliMethodChange> changes = SmaliMethodDiffParser.parseChanges("Lcom/example/Auth;", baseline, current);
		assertThat(changes).hasSize(1);

		SmaliMethodChange mc = changes.get(0);
		assertThat(mc.getMethodName()).isEqualTo("isLicensed");
		assertThat(mc.hasHunks()).isTrue();
		assertThat(mc.getHunks()).hasSize(1);

		SmaliHunk hunk = mc.getHunks().get(0);
		assertThat(hunk.getHunkIndex()).isEqualTo(1);
		assertThat(hunk.getHunkType()).isEqualTo(SmaliHunk.HunkType.REPLACE);
		assertThat(hunk.getOriginalLines()).containsExactly("    const/4 v0, 0x0");
		assertThat(hunk.getModifiedLines()).containsExactly("    const/4 v0, 0x1");

		// Execute selective revert
		SmaliMethodDiffParser.RevertResult result = SmaliMethodDiffParser.applyHunkReversion(current, hunk);
		assertThat(result.isSuccess()).isTrue();
		assertThat(result.getNewSmali()).isEqualTo(baseline);
	}

	@Test
	void testScenario4_MultipleNonOverlappingHunks() {
		String baseline = ".method public testMulti()V\n"
				+ "    .registers 4\n"
				+ "    const/4 v0, 0x0\n"
				+ "    nop\n"
				+ "    nop\n"
				+ "    const/4 v1, 0x0\n"
				+ "    return-void\n"
				+ ".end method\n";

		String current = ".method public testMulti()V\n"
				+ "    .registers 4\n"
				+ "    const/4 v0, 0x1\n"
				+ "    nop\n"
				+ "    nop\n"
				+ "    const/4 v1, 0x2\n"
				+ "    return-void\n"
				+ ".end method\n";

		List<SmaliMethodChange> changes = SmaliMethodDiffParser.parseChanges("Lcom/example/Multi;", baseline, current);
		assertThat(changes).hasSize(1);
		assertThat(changes.get(0).getHunks()).hasSize(2);

		SmaliHunk hunk1 = changes.get(0).getHunks().get(0); // Change 1: v0 = 0 -> 1
		SmaliHunk hunk2 = changes.get(0).getHunks().get(1); // Change 2: v1 = 0 -> 2

		// Revert Change 1 only
		SmaliMethodDiffParser.RevertResult res1 = SmaliMethodDiffParser.applyHunkReversion(current, hunk1);
		assertThat(res1.isSuccess()).isTrue();

		String afterRevert1 = res1.getNewSmali();
		assertThat(afterRevert1).contains("const/4 v0, 0x0"); // Hunk 1 reverted
		assertThat(afterRevert1).contains("const/4 v1, 0x2"); // Hunk 2 preserved!

		// Re-parse dynamically from afterRevert1 (Cardinal rule: no stale objects)
		List<SmaliMethodChange> changesAfter1 = SmaliMethodDiffParser.parseChanges("Lcom/example/Multi;", baseline, afterRevert1);
		assertThat(changesAfter1).hasSize(1);
		assertThat(changesAfter1.get(0).getHunks()).hasSize(1); // Only hunk 2 remains

		// Revert remaining hunk
		SmaliHunk remainingHunk = changesAfter1.get(0).getHunks().get(0);
		SmaliMethodDiffParser.RevertResult res2 = SmaliMethodDiffParser.applyHunkReversion(afterRevert1, remainingHunk);
		assertThat(res2.isSuccess()).isTrue();
		assertThat(res2.getNewSmali()).isEqualTo(baseline);
	}

	@Test
	void testScenario5_MethodLevelReversion() {
		String baseline = ".class public Lcom/example/Methods;\n"
				+ ".super Ljava/lang/Object;\n\n"
				+ ".method public originalMethod()V\n"
				+ "    return-void\n"
				+ ".end method\n";

		String addedCurrent = baseline + "\n.method public newMethod()I\n    const/4 v0, 0x7\n    return v0\n.end method\n";

		List<SmaliMethodChange> changes = SmaliMethodDiffParser.parseChanges("Lcom/example/Methods;", baseline, addedCurrent);
		assertThat(changes).hasSize(1);
		assertThat(changes.get(0).isAddedMethod()).isTrue();

		// Revert added method
		SmaliMethodDiffParser.RevertResult res = SmaliMethodDiffParser.applyMethodReversion(addedCurrent, changes.get(0));
		assertThat(res.isSuccess()).isTrue();
		assertThat(res.getNewSmali().trim()).isEqualTo(baseline.trim());
	}

	@Test
	void testScenario6_ConflictingHunk_SurroundingContextDivergence_ZeroMutation() {
		String baseline = ".method public target()V\n"
				+ "    .registers 2\n"
				+ "    const/4 v0, 0x0\n"
				+ "    invoke-virtual {v0}, Lcom/example/Foo;->bar()V\n"
				+ "    return-void\n"
				+ ".end method\n";

		String initialChange = ".method public target()V\n"
				+ "    .registers 2\n"
				+ "    const/4 v0, 0x1\n"
				+ "    invoke-virtual {v0}, Lcom/example/Foo;->bar()V\n"
				+ "    return-void\n"
				+ ".end method\n";

		// Hunk was recorded when code had const/4 v0, 0x1
		List<SmaliMethodChange> changes = SmaliMethodDiffParser.parseChanges("Lcom/example/Conflict;", baseline, initialChange);
		SmaliHunk hunkToRevert = changes.get(0).getHunks().get(0);

		// But later, user edited the target line or context to something else:
		String divergedCode = ".method public target()V\n"
				+ "    .registers 2\n"
				+ "    const/4 v0, 0x2\n"
				+ "    invoke-virtual {v0}, Lcom/example/Foo;->bar()V\n"
				+ "    return-void\n"
				+ ".end method\n";

		// Attempting to revert the stale hunk MUST detect the mismatch and abort
		SmaliMethodDiffParser.RevertResult result = SmaliMethodDiffParser.applyHunkReversion(divergedCode, hunkToRevert);
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.getErrorMessage()).contains("The current code no longer matches the expected modified state");

		// Cardinal rule: zero mutation
		assertThat(result.getNewSmali()).isNull();
	}

	@Test
	void testScenario7_AmbiguousHunk_DuplicateAnchors_ZeroMutation() {
		// Method has two identical blocks with identical surrounding context
		String methodWithDuplicates = ".method public ambiguous()V\n"
				+ "    .registers 2\n"
				+ "    nop\n"
				+ "    const/4 v0, 0x1\n"
				+ "    invoke-virtual {v0}, Lcom/example/Foo;->bar()V\n"
				+ "    return-void\n"
				+ "    nop\n"
				+ "    const/4 v0, 0x1\n"
				+ "    invoke-virtual {v0}, Lcom/example/Foo;->bar()V\n"
				+ "    return-void\n"
				+ ".end method\n";

		// Hunk whose anchors and target match BOTH identical blocks equally
		SmaliHunk ambiguousHunk = new SmaliHunk(1, "ambiguous()V", SmaliHunk.HunkType.REPLACE,
				List.of("    const/4 v0, 0x0"),
				List.of("    const/4 v0, 0x1"),
				List.of("    nop"),
				List.of("    invoke-virtual {v0}, Lcom/example/Foo;->bar()V", "    return-void"),
				3, 3, 0.45); // relative hint midway between both occurrences

		SmaliMethodDiffParser.RevertResult result = SmaliMethodDiffParser.applyHunkReversion(methodWithDuplicates, ambiguousHunk);
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.isAmbiguous()).isTrue();
		assertThat(result.getErrorMessage()).contains("Multiple ambiguous locations");
		assertThat(result.getNewSmali()).isNull();
	}

	@Test
	void testScenario7_DisambiguationViaRelativePositionHint() {
		// Method has identical blocks at line 3 and line 30
		StringBuilder sb = new StringBuilder();
		sb.append(".method public longMethod()V\n");
		sb.append("    .registers 2\n");
		sb.append("    const/4 v0, 0x1\n"); // line 3, rel ~ 0.08
		sb.append("    return-void\n");
		for (int i = 0; i < 25; i++) {
			sb.append("    nop\n");
		}
		sb.append("    const/4 v0, 0x1\n"); // line 30, rel ~ 0.90
		sb.append("    return-void\n");
		sb.append(".end method\n");

		String longMethodCode = sb.toString();

		// Hunk explicitly targeted to the first occurrence (relative hint = 0.08)
		SmaliHunk nearTopHunk = new SmaliHunk(1, "longMethod()V", SmaliHunk.HunkType.REPLACE,
				List.of("    const/4 v0, 0x0"),
				List.of("    const/4 v0, 0x1"),
				List.of(),
				List.of("    return-void"),
				3, 3, 0.08);

		SmaliMethodDiffParser.RevertResult result = SmaliMethodDiffParser.applyHunkReversion(longMethodCode, nearTopHunk);
		assertThat(result.isSuccess()).isTrue();
		// First occurrence was replaced, second occurrence was preserved!
		String[] resultLines = result.getNewSmali().split("\n");
		assertThat(resultLines[2].trim()).isEqualTo("const/4 v0, 0x0");
		assertThat(resultLines[resultLines.length - 3].trim()).isEqualTo("const/4 v0, 0x1");
	}
}
