package jadx.gui.patching;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.apksig.ApkSigner;

import jadx.gui.patching.PackageTypeDetector.PackageType;
import jadx.gui.patching.container.ApksHandler;
import jadx.gui.patching.container.XapkHandler;
import jadx.gui.patching.merger.ApkEditorBackend;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.CommonDialog;
import jadx.gui.utils.UiUtils;

public class ExportPatchedApkDialog extends CommonDialog {
	private static final Logger LOG = LoggerFactory.getLogger(ExportPatchedApkDialog.class);

	private final JTextField outputPathField = new JTextField();
	private static String lastKeystorePath = "";
	private static String lastKeyAlias = "";
	private static String lastStorePassword = "";
	private static String lastKeyPassword = "";
	private static int lastKeystoreTypeIndex = 0;

	private final JCheckBox signCheckBox = new JCheckBox("Sign APK (Installable on Android)", true);
	private final JComboBox<String> keystoreTypeCombo = new JComboBox<>(new String[]{
			"Default Android Debug Certificate",
			"Custom Keystore (.jks / .p12 / .keystore)"
	});

	private final JPanel customKeystorePanel = new JPanel(new GridBagLayout());
	private final JPanel credentialsPanel = new JPanel(new GridBagLayout());
	private final JButton toggleCredentialsBtn = new JButton("Edit Credentials");
	private final JTextField customKeystorePathField = new JTextField();
	private final JPasswordField keystorePassField = new JPasswordField();
	private final JTextField keyAliasField = new JTextField();
	private final JPasswordField keyPassField = new JPasswordField();

	private final JProgressBar progressBar = new JProgressBar();
	private final JLabel statusLabel = new JLabel(" ");
	private final JButton exportButton = new JButton("Export Patched APK");
	private final JButton cancelButton = new JButton("Cancel");

	/** Bundle-mode selection (only visible for XAPK/APKS inputs). */
	private final JRadioButton mergeRadio = new JRadioButton("Merge to Universal APK", true);
	private final JRadioButton preserveRadio = new JRadioButton("Preserve Split Container");
	private final JPanel bundleModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

	public ExportPatchedApkDialog(MainWindow mainWindow) {
		super(mainWindow);
		initUI();
	}

	private void initUI() {
		Path origInput = getOriginalInputPath();
		PackageType pkgType = origInput != null ? PackageTypeDetector.detect(origInput) : PackageType.UNKNOWN_OR_UNSUPPORTED;
		boolean dexMode = pkgType == PackageType.RAW_DEX;
		boolean bundleMode = pkgType == PackageType.XAPK_BUNDLE || pkgType == PackageType.APKS_BUNDLE;
		boolean aabMode = pkgType == PackageType.AAB_BUNDLE;
		setTitle(dexMode ? "Export Patched DEX" : "Export Patched APK");

		JPanel mainPanel = new JPanel(new GridBagLayout());
		mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.fill = GridBagConstraints.HORIZONTAL;
		int row = 0;

		// 1. Modified Classes Section
		Map<String, Map<String, ModifiedClass>> modMap = ModifiedDexManager.getInstance().getModifiedClassesByDex();
		String[] columnNames = {"Target DEX", "Modified Class Type", "Status"};
		DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0) {
			@Override
			public boolean isCellEditable(int r, int col) {
				return false;
			}
		};

		for (Map.Entry<String, Map<String, ModifiedClass>> dexEntry : modMap.entrySet()) {
			String dexName = dexEntry.getKey();
			if (dexName.contains(File.separator) || dexName.contains("/")) {
				try {
					dexName = Paths.get(dexName).getFileName().toString();
				} catch (Exception ignored) {
				}
			}
			for (ModifiedClass modCls : dexEntry.getValue().values()) {
				tableModel.addRow(new Object[]{dexName, modCls.getClassType(), "Modified (In-Memory)"});
			}
		}

		JTable table = new JTable(tableModel);
		table.setRowHeight(22);
		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setPreferredSize(new Dimension(580, 120));

		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1.0;
		mainPanel.add(new JLabel("Modified Classes (" + tableModel.getRowCount() + " in active session):"), c);

		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1.0;
		c.fill = GridBagConstraints.BOTH;
		c.weighty = 0.4;
		mainPanel.add(scrollPane, c);

		c.fill = GridBagConstraints.HORIZONTAL;
		c.weighty = 0.0;

		// Bundle Mode Selection Row (for XAPK / APKS)
		if (bundleMode) {
			ButtonGroup bg = new ButtonGroup();
			bg.add(mergeRadio);
			bg.add(preserveRadio);
			bundleModePanel.setBorder(BorderFactory.createTitledBorder("Bundle Export Mode:"));
			bundleModePanel.add(mergeRadio);
			bundleModePanel.add(preserveRadio);

			mergeRadio.addActionListener(e -> updateDefaultOutputPath(origInput, pkgType));
			preserveRadio.addActionListener(e -> updateDefaultOutputPath(origInput, pkgType));

			c.gridx = 0;
			c.gridy = row++;
			c.gridwidth = 2;
			c.weightx = 1.0;
			mainPanel.add(bundleModePanel, c);
		}

		// 2. Output Path Row
		updateDefaultOutputPath(origInput, pkgType);

		JPanel pathChooserPanel = new JPanel(new BorderLayout(6, 0));
		pathChooserPanel.add(outputPathField, BorderLayout.CENTER);
		JButton browseOutputBtn = new JButton("Browse...");
		browseOutputBtn.addActionListener(e -> chooseOutputFile());
		pathChooserPanel.add(browseOutputBtn, BorderLayout.EAST);

		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 1;
		c.weightx = 0.0;
		mainPanel.add(new JLabel(dexMode ? "Output DEX:" : "Output File:"), c);

		c.gridx = 1;
		c.weightx = 1.0;
		mainPanel.add(pathChooserPanel, c);

		if (!dexMode && !aabMode) {
			// 3. Sign Checkbox Row
			c.gridx = 0;
			c.gridy = row++;
			c.gridwidth = 1;
			c.weightx = 0.0;
			mainPanel.add(new JLabel("APK Signing:"), c);

			c.gridx = 1;
			c.weightx = 1.0;
			mainPanel.add(signCheckBox, c);

			// 4. Keystore Selector Row
			c.gridx = 0;
			c.gridy = row++;
			c.gridwidth = 1;
			c.weightx = 0.0;
			mainPanel.add(new JLabel("Keystore:"), c);

			c.gridx = 1;
			c.weightx = 1.0;
			mainPanel.add(keystoreTypeCombo, c);

			// 5. Custom Keystore Panel
			initCustomKeystorePanel();
			customKeystorePathField.setText(lastKeystorePath);
			keyAliasField.setText(lastKeyAlias);
			keystorePassField.setText(lastStorePassword);
			keyPassField.setText(lastKeyPassword);
			keystoreTypeCombo.setSelectedIndex(lastKeystoreTypeIndex);

			c.gridx = 0;
			c.gridy = row++;
			c.gridwidth = 2;
			c.weightx = 1.0;
			mainPanel.add(customKeystorePanel, c);
			boolean isCustom = lastKeystoreTypeIndex == 1;
			customKeystorePanel.setVisible(isCustom && signCheckBox.isSelected());

			keystoreTypeCombo.addActionListener(e -> {
				boolean custom = keystoreTypeCombo.getSelectedIndex() == 1;
				customKeystorePanel.setVisible(custom && signCheckBox.isSelected());
				revalidate();
				repaint();
			});

			signCheckBox.addActionListener(e -> {
				boolean enabled = signCheckBox.isSelected();
				keystoreTypeCombo.setEnabled(enabled);
				customKeystorePanel.setVisible(enabled && keystoreTypeCombo.getSelectedIndex() == 1);
				revalidate();
				repaint();
			});
		}

		// AAB Warning
		if (aabMode) {
			JLabel aabWarning = new JLabel("<html><font color='red'><b>AAB format is not supported for patching.</b><br/>"
					+ "Please use bundletool to convert AAB to APKS or APK first.</font></html>");
			c.gridx = 0;
			c.gridy = row++;
			c.gridwidth = 2;
			c.weightx = 1.0;
			mainPanel.add(aabWarning, c);
		}

		// 6. Progress & Status
		progressBar.setVisible(false);
		progressBar.setIndeterminate(true);
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1.0;
		mainPanel.add(progressBar, c);

		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1.0;
		mainPanel.add(statusLabel, c);

		// 7. Standard JADX Buttons Pane
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));
		buttonPanel.setBorder(BorderFactory.createEmptyBorder(6, 14, 12, 14));
		buttonPanel.add(Box.createHorizontalGlue());

		cancelButton.addActionListener(e -> dispose());
		exportButton.setText(dexMode ? "Export Patched DEX" : "Export Patched Package");
		if (aabMode) {
			exportButton.setEnabled(false);
		}
		exportButton.addActionListener(e -> startExportTask());

		buttonPanel.add(cancelButton);
		buttonPanel.add(Box.createRigidArea(new Dimension(8, 0)));
		buttonPanel.add(exportButton);

		getRootPane().setDefaultButton(exportButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(mainPanel, BorderLayout.CENTER);
		getContentPane().add(buttonPanel, BorderLayout.PAGE_END);

		setMinimumSize(new Dimension(650, 480));
		setSize(650, 520);
		commonWindowInit();
	}

	private void updateDefaultOutputPath(Path origInput, PackageType pkgType) {
		if (origInput == null) {
			return;
		}
		String origName = origInput.getFileName().toString();
		String ext;
		if (pkgType == PackageType.RAW_DEX) {
			ext = ".dex";
		} else if (pkgType == PackageType.XAPK_BUNDLE) {
			ext = preserveRadio.isSelected() ? ".xapk" : ".apk";
		} else if (pkgType == PackageType.APKS_BUNDLE) {
			ext = preserveRadio.isSelected() ? ".apks" : ".apk";
		} else {
			ext = ".apk";
		}

		String baseName = origName;
		int dotIdx = baseName.lastIndexOf('.');
		if (dotIdx > 0) {
			baseName = baseName.substring(0, dotIdx);
		}
		baseName = baseName.replaceAll("[-_]patched$", "");
		Path defaultOut = origInput.getParent() != null
				? origInput.getParent().resolve(baseName + "-patched" + ext)
				: Paths.get(baseName + "-patched" + ext);
		outputPathField.setText(defaultOut.toAbsolutePath().toString());
	}

	private void initCustomKeystorePanel() {
		customKeystorePanel.removeAll();
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(3, 0, 3, 0);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// 1. Keystore File Row
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 0.0;
		customKeystorePanel.add(new JLabel("Keystore File:  "), gbc);

		JPanel ksFilePanel = new JPanel(new BorderLayout(6, 0));
		ksFilePanel.add(customKeystorePathField, BorderLayout.CENTER);
		JPanel btnBox = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
		JButton browseKsBtn = new JButton("Browse...");
		browseKsBtn.addActionListener(e -> {
			chooseKeystoreFile();
			if (keystorePassField.getPassword().length == 0) {
				credentialsPanel.setVisible(true);
				toggleCredentialsBtn.setText("Hide Credentials");
			}
		});
		btnBox.add(browseKsBtn);
		btnBox.add(toggleCredentialsBtn);
		ksFilePanel.add(btnBox, BorderLayout.EAST);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		customKeystorePanel.add(ksFilePanel, gbc);

		// 2. Credentials Sub-Panel (Store Password, Alias, Key Password)
		credentialsPanel.removeAll();
		GridBagConstraints cgbc = new GridBagConstraints();
		cgbc.insets = new Insets(2, 0, 2, 0);
		cgbc.fill = GridBagConstraints.HORIZONTAL;

		// Store Pass Row
		cgbc.gridx = 0;
		cgbc.gridy = 0;
		cgbc.weightx = 0.0;
		credentialsPanel.add(new JLabel("Store Password:  "), cgbc);
		cgbc.gridx = 1;
		cgbc.weightx = 1.0;
		credentialsPanel.add(keystorePassField, cgbc);

		// Alias Row
		cgbc.gridx = 0;
		cgbc.gridy = 1;
		cgbc.weightx = 0.0;
		credentialsPanel.add(new JLabel("Key Alias:  "), cgbc);
		cgbc.gridx = 1;
		cgbc.weightx = 1.0;
		credentialsPanel.add(keyAliasField, cgbc);

		// Key Pass Row
		cgbc.gridx = 0;
		cgbc.gridy = 2;
		cgbc.weightx = 0.0;
		credentialsPanel.add(new JLabel("Key Password:  "), cgbc);
		cgbc.gridx = 1;
		cgbc.weightx = 1.0;
		credentialsPanel.add(keyPassField, cgbc);

		boolean hasSaved = !lastStorePassword.isEmpty() || !lastKeyAlias.isEmpty();
		credentialsPanel.setVisible(!hasSaved);
		toggleCredentialsBtn.setText(hasSaved ? "Edit Credentials" : "Hide Credentials");

		for (java.awt.event.ActionListener al : toggleCredentialsBtn.getActionListeners()) {
			toggleCredentialsBtn.removeActionListener(al);
		}
		toggleCredentialsBtn.addActionListener(e -> {
			boolean isVis = credentialsPanel.isVisible();
			credentialsPanel.setVisible(!isVis);
			toggleCredentialsBtn.setText(!isVis ? "Hide Credentials" : "Edit Credentials");
			revalidate();
			repaint();
		});

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;
		gbc.weightx = 1.0;
		customKeystorePanel.add(credentialsPanel, gbc);
	}

	private static final java.util.Set<String> SUPPORTED_EXTENSIONS = java.util.Set.of(
			".apk", ".dex", ".xapk", ".apks", ".apkm", ".aab"
	);

	private static boolean isSupportedFormat(Path p) {
		if (p == null) {
			return false;
		}
		String name = p.toString().toLowerCase();
		for (String ext : SUPPORTED_EXTENSIONS) {
			if (name.endsWith(ext)) {
				return true;
			}
		}
		return false;
	}

	private Path getOriginalInputPath() {
		List<Path> openPaths = mainWindow.getProject().getFilePaths();
		for (Path p : openPaths) {
			if (isSupportedFormat(p) && Files.exists(p)) {
				return p;
			}
		}
		return null;
	}

	private void chooseOutputFile() {
		Path orig = getOriginalInputPath();
		PackageType type = orig != null ? PackageTypeDetector.detect(orig) : PackageType.UNKNOWN_OR_UNSUPPORTED;
		boolean dexMode = type == PackageType.RAW_DEX;

		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(dexMode ? "Select Export DEX Destination" : "Select Export Destination");
		chooser.setFileFilter(dexMode
				? new FileNameExtensionFilter("Android DEX (*.dex)", "dex")
				: new FileNameExtensionFilter("Android Package (*.apk, *.xapk, *.apks, *.aab)", "apk", "xapk", "apks", "aab"));
		String current = outputPathField.getText().trim();
		if (!current.isEmpty()) {
			chooser.setSelectedFile(new File(current));
		}
		int res = chooser.showSaveDialog(this);
		if (res == JFileChooser.APPROVE_OPTION) {
			File f = chooser.getSelectedFile();
			outputPathField.setText(f.getAbsolutePath());
		}
	}

	private void chooseKeystoreFile() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Select Keystore File");
		chooser.setFileFilter(new FileNameExtensionFilter("Keystores (*.jks, *.p12, *.keystore)", "jks", "p12", "keystore"));
		int res = chooser.showOpenDialog(this);
		if (res == JFileChooser.APPROVE_OPTION) {
			customKeystorePathField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void startExportTask() {
		Path origInput = getOriginalInputPath();
		if (origInput == null || !Files.exists(origInput)) {
			JOptionPane.showMessageDialog(this,
					"Patching is only supported for Android APK, DEX, XAPK, and APKS files.\n"
					+ "Java JAR files (.jar) and other unsupported formats cannot be patched.",
					"Export Error",
					JOptionPane.ERROR_MESSAGE);
			return;
		}

		PackageType pkgType = PackageTypeDetector.detect(origInput);
		if (pkgType == PackageType.AAB_BUNDLE) {
			JOptionPane.showMessageDialog(this,
					"AAB format is not supported for patching.\n"
					+ "Please use bundletool to convert AAB to APKS or APK first.",
					"Export Error",
					JOptionPane.ERROR_MESSAGE);
			return;
		}

		String outStr = outputPathField.getText().trim();
		if (outStr.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Please choose an output path.", "Export Error", JOptionPane.WARNING_MESSAGE);
			return;
		}
		Path outputPath = Paths.get(outStr);

		// RAW DEX Mode
		if (pkgType == PackageType.RAW_DEX) {
			exportButton.setEnabled(false);
			cancelButton.setEnabled(false);
			progressBar.setVisible(true);
			statusLabel.setText("Patching DEX bytecode...");

			mainWindow.getBackgroundExecutor().execute("Export Patched DEX", () -> {
				try {
					ModifiedDexManager.getInstance().patchDexFile(origInput, outputPath);
					long fileSize = Files.size(outputPath);
					String sizeStr = org.apache.commons.io.FileUtils.byteCountToDisplaySize(fileSize) + " (" + fileSize + " bytes)";

					SwingUtilities.invokeLater(() -> {
						dispose();
						showSuccessDialog(outputPath, false, sizeStr, true);
					});
				} catch (Exception ex) {
					LOG.error("Failed to export patched DEX", ex);
					SwingUtilities.invokeLater(() -> {
						progressBar.setVisible(false);
						exportButton.setEnabled(true);
						cancelButton.setEnabled(true);
						statusLabel.setText("Export failed: " + ex.getMessage());
						JOptionPane.showMessageDialog(this,
								"Failed to export patched DEX:\n" + ex.getMessage(),
								"Export Error", JOptionPane.ERROR_MESSAGE);
					});
				}
			});
			return;
		}

		// Bundle Mode: Preserve Split Container
		if ((pkgType == PackageType.XAPK_BUNDLE || pkgType == PackageType.APKS_BUNDLE) && preserveRadio.isSelected()) {
			exportButton.setEnabled(false);
			cancelButton.setEnabled(false);
			progressBar.setVisible(true);
			statusLabel.setText("Patching split APKs and rebuilding container...");

			mainWindow.getBackgroundExecutor().execute("Export Patched Bundle", () -> {
				try {
					Map<String, List<ModifiedClass>> modifiedBySource = buildModifiedBySourceMap();
					if (pkgType == PackageType.XAPK_BUNDLE) {
						new XapkHandler().patchXapk(origInput, outputPath, modifiedBySource);
					} else {
						new ApksHandler().patchApks(origInput, outputPath, modifiedBySource);
					}

					long fileSize = Files.size(outputPath);
					String sizeStr = org.apache.commons.io.FileUtils.byteCountToDisplaySize(fileSize) + " (" + fileSize + " bytes)";

					SwingUtilities.invokeLater(() -> {
						dispose();
						showSuccessDialog(outputPath, false, sizeStr, false);
					});
				} catch (Exception ex) {
					LOG.error("Failed to export patched bundle container", ex);
					SwingUtilities.invokeLater(() -> {
						progressBar.setVisible(false);
						exportButton.setEnabled(true);
						cancelButton.setEnabled(true);
						statusLabel.setText("Export failed: " + ex.getMessage());
						JOptionPane.showMessageDialog(this,
								"Failed to export patched bundle:\n" + ex.getMessage(),
								"Export Error", JOptionPane.ERROR_MESSAGE);
					});
				}
			});
			return;
		}

		// Standalone APK or Bundle Merged to Universal APK
		boolean shouldSign = signCheckBox.isSelected();
		boolean isCustomKeystore = shouldSign && keystoreTypeCombo.getSelectedIndex() == 1;

		Path customKeystorePath = isCustomKeystore ? Paths.get(customKeystorePathField.getText().trim()) : null;
		char[] storePass = isCustomKeystore ? keystorePassField.getPassword() : null;
		String keyAlias = isCustomKeystore ? keyAliasField.getText().trim() : null;
		char[] keyPass = isCustomKeystore ? keyPassField.getPassword() : null;

		if (isCustomKeystore && (customKeystorePath == null || !Files.exists(customKeystorePath))) {
			JOptionPane.showMessageDialog(this, "Please select a valid custom keystore file.", "Export Error", JOptionPane.WARNING_MESSAGE);
			return;
		}

		lastKeystoreTypeIndex = keystoreTypeCombo.getSelectedIndex();
		if (isCustomKeystore) {
			lastKeystorePath = customKeystorePathField.getText().trim();
			lastKeyAlias = keyAliasField.getText().trim();
			lastStorePassword = new String(keystorePassField.getPassword());
			lastKeyPassword = new String(keyPassField.getPassword());
		}

		exportButton.setEnabled(false);
		cancelButton.setEnabled(false);
		progressBar.setVisible(true);
		statusLabel.setText("Exporting, aligning and signing APK...");

		mainWindow.getBackgroundExecutor().execute("Export Patched APK", () -> {
			Path unsignedTemp = null;
			Path alignedTemp = null;
			Path mergedTemp = null;
			try {
				Path patchSource = origInput;

				// Step 0: Merge bundle to universal APK if bundle input
				if (pkgType == PackageType.XAPK_BUNDLE || pkgType == PackageType.APKS_BUNDLE) {
					statusLabel.setText("Step 1/4: Merging split bundle to universal APK...");
					ApkEditorBackend merger = new ApkEditorBackend();
					if (!merger.isAvailable()) {
						throw new IllegalStateException("APKEditor.jar not found. Place it in JADX tools/ directory.");
					}
					mergedTemp = Files.createTempFile("jadx_export_merged_", ".apk");
					merger.mergeToUniversalApk(origInput, mergedTemp);
					patchSource = mergedTemp;
				}

				// Step 1: Patch DEX files into unsigned APK
				statusLabel.setText("Step 2/4: Patching modified DEX files into APK container...");
				unsignedTemp = Files.createTempFile("jadx_export_unsigned_", ".apk");
				ModifiedDexManager.getInstance().patchApk(patchSource, unsignedTemp);

				// Step 2: ZipAlign
				statusLabel.setText("Step 3/4: Aligning ZIP entries...");
				alignedTemp = Files.createTempFile("jadx_export_aligned_", ".apk");
				ZipAligner.align(unsignedTemp, alignedTemp);

				// Step 3: Sign
				if (shouldSign) {
					statusLabel.setText("Step 4/4: Signing APK with APK Signature Schemes v1, v2, v3...");
					ApkSigner.SignerConfig signerConfig;
					if (isCustomKeystore) {
						signerConfig = ApkSignerHelper.loadSignerConfig(customKeystorePath, storePass, keyAlias, keyPass);
					} else {
						signerConfig = ApkSignerHelper.getDefaultDebugSignerConfig();
					}
					ApkSignerHelper.sign(alignedTemp, outputPath, Collections.singletonList(signerConfig));
				} else {
					Files.move(alignedTemp, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}

				long fileSize = Files.size(outputPath);
				String sizeStr = org.apache.commons.io.FileUtils.byteCountToDisplaySize(fileSize) + " (" + fileSize + " bytes)";

				SwingUtilities.invokeLater(() -> {
					dispose();
					showSuccessDialog(outputPath, shouldSign, sizeStr, false);
				});

			} catch (Exception ex) {
				LOG.error("Failed to export patched APK", ex);
				SwingUtilities.invokeLater(() -> {
					progressBar.setVisible(false);
					exportButton.setEnabled(true);
					cancelButton.setEnabled(true);
					statusLabel.setText("Export failed: " + ex.getMessage());
					JOptionPane.showMessageDialog(this,
							"Failed to export patched APK:\n" + ex.getMessage(),
							"Export Error", JOptionPane.ERROR_MESSAGE);
				});
			} finally {
				// Ephemeral password cleanup
				if (storePass != null) {
					Arrays.fill(storePass, '\0');
				}
				if (keyPass != null) {
					Arrays.fill(keyPass, '\0');
				}
				if (unsignedTemp != null) {
					try {
						Files.deleteIfExists(unsignedTemp);
					} catch (Exception ignored) {
					}
				}
				if (alignedTemp != null) {
					try {
						Files.deleteIfExists(alignedTemp);
					} catch (Exception ignored) {
					}
				}
				if (mergedTemp != null) {
					try {
						Files.deleteIfExists(mergedTemp);
					} catch (Exception ignored) {
					}
				}
			}
		});
	}

	private Map<String, List<ModifiedClass>> buildModifiedBySourceMap() {
		Map<String, List<ModifiedClass>> map = new LinkedHashMap<>();
		for (ModifiedClass mc : ModifiedDexManager.getInstance().getModifiedClasses()) {
			String src = mc.getSourceApkName();
			if (src == null || src.isEmpty()) {
				src = "base.apk"; // fallback if no specific split recorded
			}
			map.computeIfAbsent(src, k -> new ArrayList<>()).add(mc);
		}
		return map;
	}

	private void showSuccessDialog(Path outputPath, boolean signed, String sizeStr, boolean isDex) {
		String msg = isDex
				? String.format("Successfully exported patched DEX:\n%s\n\nSize: %s", outputPath.toAbsolutePath(), sizeStr)
				: String.format("Successfully exported patched package:\n%s\n\nSize: %s\nSigned: %s",
						outputPath.toAbsolutePath(), sizeStr, signed ? "Yes (v1, v2, v3 schemes)" : "No (Unsigned / Container)");

		Object[] options = {"Open in Folder", "Reload Original (Reset)", "Done"};
		int res = JOptionPane.showOptionDialog(
				mainWindow,
				msg,
				"Export Complete",
				JOptionPane.DEFAULT_OPTION,
				JOptionPane.INFORMATION_MESSAGE,
				null,
				options,
				options[0]);

		if (res == 0) {
			openInFolder(outputPath);
		} else if (res == 1) {
			UiUtils.uiRun(mainWindow::reopen);
		}
	}

	private void openInFolder(Path path) {
		try {
			Path absPath = path.toAbsolutePath().normalize();
			Path parent = absPath.getParent();
			String os = System.getProperty("os.name", "").toLowerCase();
			if (os.contains("win")) {
				if (Files.exists(absPath)) {
					new ProcessBuilder("explorer.exe", "/select,", absPath.toString()).start();
					return;
				} else if (parent != null && Files.exists(parent)) {
					new ProcessBuilder("explorer.exe", parent.toString()).start();
					return;
				}
			} else if (os.contains("mac")) {
				if (Files.exists(absPath)) {
					new ProcessBuilder("open", "-R", absPath.toString()).start();
					return;
				}
			} else if (os.contains("nix") || os.contains("nux")) {
				if (parent != null && Files.exists(parent)) {
					new ProcessBuilder("xdg-open", parent.toString()).start();
					return;
				}
			}
			// Fallback
			if (Desktop.isDesktopSupported()) {
				if (parent != null && Files.exists(parent)) {
					Desktop.getDesktop().open(parent.toFile());
				} else if (Files.exists(absPath)) {
					Desktop.getDesktop().open(absPath.toFile());
				}
			}
		} catch (Exception e) {
			LOG.warn("Failed to open folder for: {}", path, e);
		}
	}
}
