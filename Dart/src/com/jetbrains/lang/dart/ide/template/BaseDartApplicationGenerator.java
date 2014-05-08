package com.jetbrains.lang.dart.ide.template;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableModelsProvider;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.IdeBorderFactory;
import com.jetbrains.lang.dart.ide.module.DartProjectTemplate;
import com.jetbrains.lang.dart.ide.runner.client.DartiumUtil;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkGlobalLibUtil;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import com.jetbrains.lang.dart.util.PubspecYamlUtil;
import icons.DartIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public abstract class BaseDartApplicationGenerator extends DartProjectTemplate<DartProjectWizardData> {

  private class MyDialogWrapper extends DialogWrapper {

    private final GeneratorPeer myPeer;
    private final JComponent myCenterComponent;
    private final JTextPane myDescriptionPane;

    protected MyDialogWrapper(@NotNull GeneratorPeer<DartProjectWizardData> peer) {
      super(true);
      myPeer = peer;
      myCenterComponent = peer.getComponent();
      final Integer preferredDescriptionWidth = getPreferredDescriptionWidth();
      if (preferredDescriptionWidth == null) {
        myDescriptionPane = new JTextPane();
      }
      else {
        myDescriptionPane = new JTextPane() {
          @Override
          public Dimension getPreferredSize() {
            // This trick makes text component to carry text over to the next line
            // iff the text line width exceeds parent's width
            Dimension dimension = super.getPreferredSize();
            dimension.width = preferredDescriptionWidth;
            return dimension;
          }
        };
      }
      myDescriptionPane.setBorder(IdeBorderFactory.createEmptyBorder(5, 0, 10, 0));
      Messages.configureMessagePaneUi(myDescriptionPane, getDescription());

      getOKAction().setEnabled(peer.validate() == null);
      peer.addSettingsStateListener(new SettingsStateListener() {
        @Override
        public void stateChanged(boolean validSettings) {
          getOKAction().setEnabled(validSettings);
        }
      });
      setTitle(BaseDartApplicationGenerator.this.getName());
      init();
    }

    @Nullable
    @Override
    protected String getHelpId() {
      return BaseDartApplicationGenerator.this.getHelpId();
    }

    @Override
    protected boolean postponeValidation() {
      return false;
    }

    @Override
    protected ValidationInfo doValidate() {
      ValidationInfo validationInfo = myPeer.validate();
      if (validationInfo != null && myPeer.isBackgroundJobRunning()) {
        return null;
      }
      return validationInfo;
    }

    @Nullable
    @Override
    protected JComponent createNorthPanel() {
      return myDescriptionPane;
    }

    @Override
    protected JComponent createCenterPanel() {
      return myCenterComponent;
    }
  }

  @Nullable
  public Integer getPreferredDescriptionWidth() {
    return null;
  }

  @Nullable
  public String getHelpId() { return null; }

  public Icon getIcon() {
    return DartIcons.Dart_16;
  }

  public void generateProject(final @NotNull Project project,
                              final @NotNull VirtualFile baseDir,
                              final @Nullable DartProjectWizardData data,
                              final @NotNull Module module) {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        setupSdkAndDartium(module, data);
        try {
          createContents(baseDir, module, project);
          final VirtualFile pubspecYamlFile = createPubspec(baseDir);
          setPubspecContent(pubspecYamlFile, module);
        }
        catch (IOException ignore) {/* unlucky */}
      }
    });
  }

  @NotNull
  @Override
  public GeneratorPeer<DartProjectWizardData> createPeer() { return new DartGeneratorPeer(); }

  @Nullable
  @Override
  public DartProjectWizardData showGenerationSettings(final VirtualFile baseDir) throws ProcessCanceledException {
    GeneratorPeer<DartProjectWizardData> peer = createPeer();
    DialogWrapper dialog = new MyDialogWrapper(peer);
    dialog.show();
    if (dialog.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
      throw new ProcessCanceledException();
    }
    return peer.getSettings();
  }

  protected  void openFile(final VirtualFile file, final Project project) {
    final Runnable runnable = new Runnable() {
      @Override
      public void run() {
        FileEditorManager.getInstance(project).openFile(file, true);
      }
    };

    if (project.isInitialized()) {
      runnable.run();
    }
    else {
      StartupManager.getInstance(project).registerPostStartupActivity(runnable);
    }
  }

  protected VirtualFile createPubspec(final VirtualFile baseDir) throws IOException {
    return baseDir.createChildData(BaseDartApplicationGenerator.this, PubspecYamlUtil.PUBSPEC_YAML);
  }

  protected void setPubspecContent(final VirtualFile pubspecYamlFile, final Module module) throws IOException {
    // Default is empty
  }

  protected void createContents(final VirtualFile baseDir, final Module module, Project project) throws IOException {
    // Default is none
  }

  protected String toTitleCase(String str) {
    if (str.length() < 2) {
      return str.toUpperCase();
    } else {
      return str.substring(0, 1).toUpperCase() + str.substring(1).replaceAll("_", " ");
    }
  }

  private static void setupSdkAndDartium(final Module module, final DartProjectWizardData data) {
    // similar to DartConfigurable.apply()
    final ModifiableModelsProvider modifiableModelsProvider = ModifiableModelsProvider.SERVICE.getInstance();
    if (DartSdkUtil.isDartSdkHome(data.dartSdkPath)) {
      final LibraryTable.ModifiableModel libraryTableModifiableModel = modifiableModelsProvider.getLibraryTableModifiableModel();

      final DartSdk sdk = DartSdk.findDartSdkAmongGlobalLibs(libraryTableModifiableModel.getLibraries());
      final String dartSdkLibName;

      if (sdk == null) {
        dartSdkLibName = DartSdkGlobalLibUtil.createDartSdkGlobalLib(libraryTableModifiableModel, data.dartSdkPath);
      }
      else {
        dartSdkLibName = sdk.getGlobalLibName();

        if (!data.dartSdkPath.equals(sdk.getHomePath())) {
          DartSdkGlobalLibUtil.updateDartSdkGlobalLib(libraryTableModifiableModel, dartSdkLibName, data.dartSdkPath);
        }
      }

      final Library dartSdkGlobalLib = libraryTableModifiableModel.getLibraryByName(dartSdkLibName);
      assert dartSdkGlobalLib != null;

      if (libraryTableModifiableModel.isChanged()) {
        libraryTableModifiableModel.commit();
      }

      // similar to DartSdkGlobalLibUtil.configureDependencyOnGlobalLib
      final ModifiableRootModel moduleModifiableModel = modifiableModelsProvider.getModuleModifiableModel(module);
      moduleModifiableModel.addLibraryEntry(dartSdkGlobalLib);
      modifiableModelsProvider.commitModuleModifiableModel(moduleModifiableModel);
    }

    DartiumUtil.applyDartiumSettings(FileUtilRt.toSystemIndependentName(data.dartiumPath), data.dartiumSettings);
  }

}