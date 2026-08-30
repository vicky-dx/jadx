package jadx.plugins.input.smali;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.TokenStream;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.runtime.tree.TreeNodeStream;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali.smaliFlexLexer;
import com.android.tools.smali.smali.smaliParser;
import com.android.tools.smali.smali.smaliTreeWalker;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods to assemble smali to in-memory buffer.
 * This implementation uses smali library internal classes.
 */
public class SmaliUtils {

	public static byte[] assemble(String smaliCode) {
		SmaliOptions options = new SmaliOptions();
		options.apiLevel = 27;
		options.verboseErrors = true;
		options.allowOdexOpcodes = false;
		options.printTokens = false;
		return assemble(smaliCode, options);
	}

	public static byte[] assemble(String smaliCode, SmaliOptions options) {
		List<String> classBlocks = splitClasses(smaliCode);
		if (classBlocks.isEmpty()) {
			throw new RuntimeException("No class definition found in smali code");
		}
		DexBuilder dexBuilder = new DexBuilder(Opcodes.forApi(options.apiLevel));
		StringBuilder errors = new StringBuilder();
		for (int i = 0; i < classBlocks.size(); i++) {
			String classBlock = classBlocks.get(i);
			try (StringReader reader = new StringReader(classBlock)) {
				assembleClass(reader, "memory_cls_" + i + ".smali", options, dexBuilder, errors);
			}
		}
		try {
			MemoryDataStore dataStore = new MemoryDataStore();
			dexBuilder.writeTo(dataStore);
			return dataStore.getData();
		} catch (IOException e) {
			throw new RuntimeException("Smali process error: " + errors, e);
		}
	}

	public static List<String> splitClasses(String smaliCode) {
		List<String> classes = new ArrayList<>();
		String[] lines = smaliCode.split("\\r?\\n");
		StringBuilder currentClass = new StringBuilder();
		boolean inClass = false;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith(".class ")) {
				if (inClass && currentClass.length() > 0) {
					classes.add(currentClass.toString());
					currentClass.setLength(0);
				}
				inClass = true;
			}
			currentClass.append(line).append('\n');
		}
		if (currentClass.length() > 0) {
			classes.add(currentClass.toString());
		}
		return classes;
	}

	@SuppressWarnings("ExtractMethodRecommender")
	public static byte[] assemble(File smaliFile, SmaliOptions options) throws IOException {
		try (FileInputStream fis = new FileInputStream(smaliFile);
				InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
			return assemble(reader, smaliFile.getName(), options);
		}
	}

	public static byte[] assemble(Reader reader, String sourceName, SmaliOptions options) {
		DexBuilder dexBuilder = new DexBuilder(Opcodes.forApi(options.apiLevel));
		StringBuilder errors = new StringBuilder();
		assembleClass(reader, sourceName, options, dexBuilder, errors);
		try {
			MemoryDataStore dataStore = new MemoryDataStore();
			dexBuilder.writeTo(dataStore);
			return dataStore.getData();
		} catch (IOException e) {
			throw new RuntimeException("Smali process error: " + errors, e);
		}
	}

	private static void assembleClass(Reader reader, String sourceName, SmaliOptions options, DexBuilder dexBuilder, StringBuilder errors) {
		try {
			smaliFlexLexer lexer = new smaliFlexLexer(reader, options.apiLevel);
			if (sourceName != null) {
				lexer.setSourceFile(new File(sourceName));
			}
			CommonTokenStream tokens = new CommonTokenStream(lexer);
			ParserWrapper parser = new ParserWrapper(tokens, errors);
			parser.setVerboseErrors(options.verboseErrors);
			parser.setAllowOdex(options.allowOdexOpcodes);
			parser.setApiLevel(options.apiLevel);
			ParserWrapper.smali_file_return parseResult = parser.smali_file();
			if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0) {
				throw new RuntimeException("Smali parse error: " + errors);
			}
			CommonTreeNodeStream treeStream = new CommonTreeNodeStream(parseResult.getTree());
			treeStream.setTokenStream(tokens);

			TreeWalkerWrapper dexGen = new TreeWalkerWrapper(treeStream, errors);
			dexGen.setApiLevel(options.apiLevel);
			dexGen.setVerboseErrors(options.verboseErrors);
			dexGen.setDexBuilder(dexBuilder);
			dexGen.smali_file();
			if (dexGen.getNumberOfSyntaxErrors() > 0) {
				throw new RuntimeException("Smali compile error: " + errors);
			}
		} catch (RecognitionException e) {
			throw new RuntimeException("Smali process error: " + errors, e);
		}
	}

	private static final class ParserWrapper extends smaliParser {
		private final StringBuilder errors;

		public ParserWrapper(TokenStream input, StringBuilder errors) {
			super(input);
			this.errors = errors;
		}

		@Override
		public void emitErrorMessage(String msg) {
			errors.append('\n').append(msg);
		}
	}

	private static final class TreeWalkerWrapper extends smaliTreeWalker {
		private final StringBuilder errors;

		public TreeWalkerWrapper(TreeNodeStream input, StringBuilder errors) {
			super(input);
			this.errors = errors;
		}

		@Override
		public void emitErrorMessage(String msg) {
			errors.append('\n').append(msg);
		}
	}
}
