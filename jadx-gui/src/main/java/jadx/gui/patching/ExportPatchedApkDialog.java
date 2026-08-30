package jadx.gui.patching;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.apksig.ApkSigner;

import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.CommonDialog;
import jadx.gui.utils.UiUtils;

public class ExportPatchedApkDialog extends CommonDialog {
	private static final Logger LOG = LoggerFactory.getLogger(ExportPatchedApkDialog.class);

	private final JTextField outputPathField = new JTextField();
	private final JCheckBox signCheckBox = new JCheckBox("Sign APK (Installable on Android)", true);
	private final JComboBox<String> keystoreTypeCombo = new JComboBox<>(new String[]{
			"Default Android Debug Certificate",
			"Custom Keystore (.jks / .p12 / .keystore)"
	});

	private final JPanel customKeystorePanel = new JPanel(new GridBagLayout());
	private final JTextField customKeystorePathField = new JTextField();
	private final JPasswordField keystorePassField = new JPasswordField();
	private final JTextField keyAliasField = new JTextField();
	private final JPasswordField keyPassField = new JPasswordField();

	private final JProgressBar progressBar = new JProgressBar();
	private final JLabel statusLabel = new JLabel(" ");
	private final JButton exportButton = new JButton("Export Patched APK");
	private final JButton cancelButton = new JButton("Cancel");

	public ExportPatchedApkDialog(MainWindow mainWindow) {
		super(mainWindow);
		setTitle("Export Patched APK");
		initUI();
	}

	private void initUI() {
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
			for (String classType : dexEntry.getValue().keySet()) {
				tableModel.addRow(new Object[]{dexName, classType, "Modified (In-Memory)"});
			}
		}

		JLabel tableLabel = new JLabel(String.format("Modified Classes (%d in active session):", tableModel.getRowCount()));
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1.0;
		mainPanel.add(tableLabel, c);

		JTable classesTable = new JTable(tableModel);
		classesTable.setRowHeight(22);
		JScrollPane scrollPane = new JScrollPane(classesTable);
		scrollPane.setPreferredSize(new Dimension(580, 110));
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1.0;
		c.fill = GridBagConstraints.BOTH;
		c.weighty = 0.4;
		mainPanel.add(scrollPane, c);

		c.fill = GridBagConstraints.HORIZONTAL;
		c.weighty = 0.0;

		// 2. Output Path Row
		Path origApk = getOriginalApkPath();
		if (origApk != null) {
			String origName = origApk.getFileName().toString();
			String baseName = origName.toLowerCase().endsWith(".apk") ? origName.substring(0, origName.length() - 4) : origName;
			baseName = baseName.replaceAll("[-_]patched$", "");
			Path defaultOut = origApk.getParent() != null
					? origApk.getParent().resolve(baseName + "-patched.apk")
					: Paths.get(baseName + "-patched.apk");
			outputPathField.setText(defaultOut.toAbsolutePath().toString());
		}

		JPanel pathChooserPanel = new JPanel(new BorderLayout(6, 0));
		pathChooserPanel.add(outputPathField, BorderLayout.CENTER);
		JButton browseOutputBtn = new JButton("Browse...");
		browseOutputBtn.addActionListener(e -> chooseOutputFile());
		pathChooserPanel.add(browseOutputBtn, BorderLayout.EAST);

		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 1;
		c.weightx = 0.0;
		mainPanel.add(new JLabel("Output APK:"), c);

		c.gridx = 1;
		c.weightx = 1.0;
		mainPanel.add(pathChooserPanel, c);

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
		c.gridx = 0;
		c.gridy = row++;
		c.gridwidth = 2;
		c.weightx = 1.0;
		mainPanel.add(customKeystorePanel, c);
		customKeystorePanel.setVisible(false);

		keystoreTypeCombo.addActionListener(e -> {
			boolean isCustom = keystoreTypeCombo.getSelectedIndex() == 1;
			customKeystorePanel.setVisible(isCustom && signCheckBox.isSelected());
			pack();
		});

		signCheckBox.addActionListener(e -> {
			boolean enabled = signCheckBox.isSelected();
			keystoreTypeCombo.setEnabled(enabled);
			customKeystorePanel.setVisible(enabled && keystoreTypeCombo.getSelectedIndex() == 1);
			pack();
		});

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
		exportButton.addActionListener(e -> startExportTask());

		buttonPanel.add(cancelButton);
		buttonPanel.add(Box.createRigidArea(new Dimension(8, 0)));
		buttonPanel.add(exportButton);

		getRootPane().setDefaultButton(exportButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(mainPanel, BorderLayout.CENTER);
		getContentPane().add(buttonPanel, BorderLayout.PAGE_END);

		commonWindowInit();
	}

	private void initCustomKeystorePanel() {
		customKeystorePanel.removeAll();
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(3, 0, 3, 0);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Keystore File Row
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 0.0;
		customKeystorePanel.add(new JLabel("Keystore File:  "), gbc);

		JPanel ksFilePanel = new JPanel(new BorderLayout(6, 0));
		ksFilePanel.add(customKeystorePathField, BorderLayout.CENTER);
		JButton browseKsBtn = new JButton("Browse...");
		browseKsBtn.addActionListener(e -> chooseKeystoreFile());
		ksFilePanel.add(browseKsBtn, BorderLayout.EAST);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		customKeystorePanel.add(ksFilePanel, gbc);

		// Store Pass Row
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.weightx = 0.0;
		customKeystorePanel.add(new JLabel("Store Password:  "), gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		customKeystorePanel.add(keystorePassField, gbc);

		// Alias Row
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0.0;
		customKeystorePanel.add(new JLabel("Key Alias:  "), gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		customKeystorePanel.add(keyAliasField, gbc);

		// Key Pass Row
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.weightx = 0.0;
		customKeystorePanel.add(new JLabel("Key Password:  "), gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		customKeystorePanel.add(keyPassField, gbc);
	}

	private Path getOriginalApkPath() {
		List<Path> openPaths = mainWindow.getProject().getFilePaths();
		for (Path p : openPaths) {
			if (p.toString().toLowerCase().endsWith(".apk") && Files.exists(p)) {
				return p;
			}
		}
		if (!openPaths.isEmpty() && Files.exists(openPaths.get(0))) {
			return openPaths.get(0);
		}
		return null;
	}

	private void chooseOutputFile() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Select Export APK Destination");
		chooser.setFileFilter(new FileNameExtensionFilter("Android APK (*.apk)", "apk"));
		String current = outputPathField.getText().trim();
		if (!current.isEmpty()) {
			chooser.setSelectedFile(new File(current));
		}
		int res = chooser.showSaveDialog(this);
		if (res == JFileChooser.APPROVE_OPTION) {
			File f = chooser.getSelectedFile();
			String path = f.getAbsolutePath();
			if (!path.toLowerCase().endsWith(".apk")) {
				path += ".apk";
			}
			outputPathField.setText(path);
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
		Path origApk = getOriginalApkPath();
		if (origApk == null || !Files.exists(origApk)) {
			JOptionPane.showMessageDialog(this, "Original APK not found.", "Export Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		String outStr = outputPathField.getText().trim();
		if (outStr.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Please choose an output path.", "Export Error", JOptionPane.WARNING_MESSAGE);
			return;
		}
		Path outputApk = Paths.get(outStr);

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

		exportButton.setEnabled(false);
		cancelButton.setEnabled(false);
		progressBar.setVisible(true);
		statusLabel.setText("Exporting, aligning and signing APK...");

		mainWindow.getBackgroundExecutor().execute("Export Patched APK", () -> {
			Path unsignedTemp = null;
			Path alignedTemp = null;
			try {
				// 1. Patch DEX files into unsigned APK
				statusLabel.setText("Step 1/3: Patching modified DEX files into APK container...");
				unsignedTemp = Files.createTempFile("jadx_export_unsigned_", ".apk");
				ModifiedDexManager.getInstance().patchApk(origApk, unsignedTemp);

				// 2. ZipAlign
				statusLabel.setText("Step 2/3: Aligning ZIP entries...");
				alignedTemp = Files.createTempFile("jadx_export_aligned_", ".apk");
				ZipAligner.align(unsignedTemp, alignedTemp);

				// 3. Sign
				if (shouldSign) {
					statusLabel.setText("Step 3/3: Signing APK with APK Signature Schemes v1, v2, v3...");
					ApkSigner.SignerConfig signerConfig;
					if (isCustomKeystore) {
						signerConfig = ApkSignerHelper.loadSignerConfig(customKeystorePath, storePass, keyAlias, keyPass);
					} else {
						signerConfig = ApkSignerHelper.getDefaultDebugSignerConfig();
					}
					ApkSignerHelper.sign(alignedTemp, outputApk, Collections.singletonList(signerConfig));
				} else {
					Files.move(alignedTemp, outputApk, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}

				long fileSize = Files.size(outputApk);
				String sizeStr = org.apache.commons.io.FileUtils.byteCountToDisplaySize(fileSize) + " (" + fileSize + " bytes)";

				SwingUtilities.invokeLater(() -> {
					dispose();
					showSuccessDialog(outputApk, shouldSign, sizeStr);
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
			}
		});
	}

	private void showSuccessDialog(Path outputApk, boolean signed, String sizeStr) {
		String msg = String.format("Successfully exported patched APK:\n%s\n\nSize: %s\nSigned: %s (Installable on Android)",
				outputApk.toAbsolutePath(), sizeStr, signed ? "Yes (v1, v2, v3 schemes)" : "No (Unsigned)");

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
			try {
				Path parent = outputApk.getParent();
				if (parent != null && Desktop.isDesktopSupported()) {
					Desktop.getDesktop().open(parent.toFile());
				}
			} catch (Exception e) {
				LOG.warn("Failed to open output directory", e);
			}
		} else if (res == 1) {
			UiUtils.uiRun(mainWindow::reopen);
		}
	}
}
