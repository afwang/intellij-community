// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.idea;

import com.intellij.concurrency.IdeaForkJoinWorkerThreadFactory;
import com.intellij.ide.ClassUtilCore;
import com.intellij.ide.cloudConfig.CloudConfigProvider;
import com.intellij.ide.customize.CustomizeIDEWizardDialog;
import com.intellij.ide.customize.CustomizeIDEWizardStepsProvider;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.startup.StartupActionScriptManager;
import com.intellij.ide.startupWizard.StartupWizard;
import com.intellij.jna.JnaLoader;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.ConfigImportHelper;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.ShutDownTracker;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.io.win32.IdeaWin32;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AppUIUtil;
import com.intellij.util.Consumer;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.PlatformUtils;
import com.intellij.util.SystemProperties;
import com.intellij.util.ui.UIUtil;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.io.BuiltInServer;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * @author yole
 */
public class StartupUtil {
  public static final String NO_SPLASH = "nosplash";

  private static SocketLock ourSocketLock;

  private StartupUtil() { }

  public static boolean shouldShowSplash(final String[] args) {
    if ("true".equals(System.getProperty(NO_SPLASH))) {
      return false;
    }
    return !Arrays.asList(args).contains(NO_SPLASH);
  }

  public static synchronized void addExternalInstanceListener(@Nullable Consumer<List<String>> consumer) {
    // method called by app after startup
    if (ourSocketLock != null) {
      ourSocketLock.setExternalInstanceListener(consumer);
    }
  }

  @Nullable
  public static synchronized BuiltInServer getServer() {
    return ourSocketLock == null ? null : ourSocketLock.getServer();
  }

  @FunctionalInterface
  interface AppStarter {
    void start(boolean newConfigFolder);

    default void beforeImportConfigs() {}
  }

  static void prepareAndStart(String[] args, AppStarter appStarter) {
    IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(Main.isHeadless(args));
    boolean newConfigFolder = false;

    checkHiDPISettings();

    if (!Main.isHeadless()) {
      AppUIUtil.updateFrameClass();
      newConfigFolder = !new File(PathManager.getConfigPath()).exists();
    }

    if (!checkJdkVersion()) {
      System.exit(Main.JDK_CHECK_FAILED);
    }

    // avoiding "log4j:WARN No appenders could be found"
    System.setProperty("log4j.defaultInitOverride", "true");
    try {
      org.apache.log4j.Logger root = org.apache.log4j.Logger.getRootLogger();
      if (!root.getAllAppenders().hasMoreElements()) {
        root.setLevel(Level.WARN);
        root.addAppender(new ConsoleAppender(new PatternLayout(PatternLayout.DEFAULT_CONVERSION_PATTERN)));
      }
    }
    catch (Throwable e) {
      //noinspection CallToPrintStackTrace
      e.printStackTrace();
    }

    // note: uses config folder!
    if (!checkSystemFolders()) {
      System.exit(Main.DIR_CHECK_FAILED);
    }

    ActivationResult result = lockSystemFolders(args);
    if (result == ActivationResult.ACTIVATED) {
      System.exit(0);
    }
    if (result != ActivationResult.STARTED) {
      System.exit(Main.INSTANCE_CHECK_FAILED);
    }

    // the log initialization should happen only after locking the system directory
    Logger.setFactory(LoggerFactory.class);
    Logger log = Logger.getInstance(Main.class);
    startLogging(log);
    loadSystemLibraries(log);
    fixProcessEnvironment(log);

    if (!Main.isHeadless()) {
      UIUtil.initDefaultLAF();
    }

    if (newConfigFolder) {
      appStarter.beforeImportConfigs();
      ConfigImportHelper.importConfigsTo(PathManager.getConfigPath());
    }
    else {
      installPluginUpdates();
    }

    if (!Main.isHeadless()) {
      AppUIUtil.updateWindowIcon(JOptionPane.getRootFrame());
      AppUIUtil.registerBundledFonts();
      AppUIUtil.showUserAgreementAndConsentsIfNeeded();
    }

    appStarter.start(newConfigFolder);
  }

  /**
   * Checks if the program can run under the JDK it was started with.
   */
  private static boolean checkJdkVersion() {
    if ("true".equals(System.getProperty("idea.jre.check"))) {
      try {
        // try to find a JDK class
        Class.forName("com.sun.jdi.Field", false, StartupUtil.class.getClassLoader());
      }
      catch (ClassNotFoundException e) {
        String message = "JDK classes seem to be not on " + ApplicationNamesInfo.getInstance().getProductName() + " classpath.\n" +
                         "Please ensure you run the IDE on JDK rather than JRE.";
        Main.showMessage("JDK Required", message, true);
        return false;
      }
      catch (LinkageError e) {
        String message = "Cannot load a JDK class: " + e.getMessage() + "\n" +
                         "Please ensure you run the IDE on JDK rather than JRE.";
        Main.showMessage("JDK Required", message, true);
        return false;
      }
    }

    if ("true".equals(System.getProperty("idea.64bit.check"))) {
      if (PlatformUtils.isCidr() && !SystemInfo.is64Bit) {
        String message = "32-bit JVM is not supported. Please use 64-bit version.";
        Main.showMessage("Unsupported JVM", message, true);
        return false;
      }
    }

    return true;
  }

  @TestOnly
  public static void test_checkHiDPISettings() {
    checkHiDPISettings();
  }

  private static void checkHiDPISettings() {
    if (!SystemProperties.getBooleanProperty("hidpi", true)) {
      // suppress JRE-HiDPI mode
      System.setProperty("sun.java2d.uiScale.enabled", "false");
    }
  }

  private static synchronized boolean checkSystemFolders() {
    String configPath = PathManager.getConfigPath();
    PathManager.ensureConfigFolderExists();
    if (!new File(configPath).isDirectory()) {
      String message = "Config path '" + configPath + "' is invalid.\n\n" +
                       "If you have modified the '" + PathManager.PROPERTY_CONFIG_PATH + "' property, please make sure it is correct,\n" +
                       "otherwise please re-install the IDE.";
      Main.showMessage("Invalid Config Path", message, true);
      return false;
    }

    String systemPath = PathManager.getSystemPath();
    if (!new File(systemPath).isDirectory()) {
      String message = "System path '" + systemPath + "' is invalid.\n\n" +
                       "If you have modified the '" + PathManager.PROPERTY_SYSTEM_PATH + "' property, please make sure it is correct,\n" +
                       "otherwise please re-install the IDE.";
      Main.showMessage("Invalid System Path", message, true);
      return false;
    }

    if (FileUtil.pathsEqual(configPath, systemPath)) {
      String message = "Config and system paths seem to be equal.\n\n" +
                       "If you have modified '" + PathManager.PROPERTY_CONFIG_PATH + "' or '" + PathManager.PROPERTY_SYSTEM_PATH + "' properties,\n" +
                       "please make sure they point to different directories, otherwise please re-install the IDE.";
      Main.showMessage("Invalid Config or System Path", message, true);
      return false;
    }

    File logDir = new File(PathManager.getLogPath());
    boolean logOk = false;
    if (logDir.isDirectory() || logDir.mkdirs()) {
      try {
        File ideTempFile = new File(logDir, "idea_log_check.txt");
        write(ideTempFile, "log check");
        delete(ideTempFile);
        logOk = true;
      }
      catch (IOException ignored) { }
    }
    if (!logOk) {
      String message = "Log path '" + logDir.getPath() + "' is inaccessible.\n\n" +
                       "If you have modified the '" + PathManager.PROPERTY_LOG_PATH + "' property please make sure it is correct,\n" +
                       "otherwise please re-install the IDE.";
      Main.showMessage("Invalid Log Path", message, true);
      return false;
    }

    File ideTempDir = new File(PathManager.getTempPath());
    String tempInaccessible;

    if (!ideTempDir.isDirectory() && !ideTempDir.mkdirs()) {
      tempInaccessible = "unable to create the directory";
    }
    else {
      try {
        File ideTempFile = new File(ideTempDir, "idea_tmp_check.sh");
        write(ideTempFile, "#!/bin/sh\nexit 0");

        if (SystemInfo.isWindows || SystemInfo.isMac) {
          tempInaccessible = null;
        }
        else if (!ideTempFile.setExecutable(true, true)) {
          tempInaccessible = "cannot set executable permission";
        }
        else if (new ProcessBuilder(ideTempFile.getAbsolutePath()).start().waitFor() != 0) {
          tempInaccessible = "cannot execute test script";
        }
        else {
          tempInaccessible = null;
        }

        delete(ideTempFile);
      }
      catch (Exception e) {
        tempInaccessible = e.getClass().getSimpleName() + ": " + e.getMessage();
      }
    }

    if (tempInaccessible != null) {
      String message = "Temp directory '" + ideTempDir + "' is inaccessible.\n\n" +
                       "If you have modified the '" + PathManager.PROPERTY_SYSTEM_PATH + "' property please make sure it is correct,\n" +
                       "otherwise please re-install the IDE.\n\nDetails: " + tempInaccessible;
      Main.showMessage("Invalid System Path", message, true);
      return false;
    }

    return true;
  }

  private static void write(File file, String content) throws IOException {
    try (FileWriter writer = new FileWriter(file)) {
      writer.write(content);
    }
  }

  @SuppressWarnings("SSBasedInspection")
  private static void delete(File ideTempFile) {
    if (!FileUtilRt.delete(ideTempFile)) {
      ideTempFile.deleteOnExit();
    }
  }

  private enum ActivationResult { STARTED, ACTIVATED, FAILED }

  @NotNull
  private static synchronized ActivationResult lockSystemFolders(String[] args) {
    if (ourSocketLock != null) {
      throw new AssertionError();
    }

    ourSocketLock = new SocketLock(PathManager.getConfigPath(), PathManager.getSystemPath());

    SocketLock.ActivateStatus status;
    try {
      status = ourSocketLock.lock(args);
    }
    catch (Exception e) {
      Main.showMessage("Cannot Lock System Folders", e);
      return ActivationResult.FAILED;
    }

    if (status == SocketLock.ActivateStatus.NO_INSTANCE) {
      ShutDownTracker.getInstance().registerShutdownTask(() -> {
        //noinspection SynchronizeOnThis
        synchronized (StartupUtil.class) {
          ourSocketLock.dispose();
          ourSocketLock = null;
        }
      });
      return ActivationResult.STARTED;
    }
    if (status == SocketLock.ActivateStatus.ACTIVATED) {
      //noinspection UseOfSystemOutOrSystemErr
      System.out.println("Already running");
      return ActivationResult.ACTIVATED;
    }
    if (Main.isHeadless() || status == SocketLock.ActivateStatus.CANNOT_ACTIVATE) {
      String message = "Only one instance of " + ApplicationNamesInfo.getInstance().getProductName() + " can be run at a time.";
      Main.showMessage("Too Many Instances", message, true);
    }

    return ActivationResult.FAILED;
  }

  private static void fixProcessEnvironment(Logger log) {
    if (!Main.isCommandLine()) {
      System.setProperty("__idea.mac.env.lock", "unlocked");
    }
    boolean envReady = EnvironmentUtil.isEnvironmentReady();  // trigger environment loading
    if (!envReady) {
      log.info("initializing environment");
    }
  }

  private static void loadSystemLibraries(final Logger log) {
    // load JNA in own temp directory - to avoid collisions and work around no-exec /tmp
    File ideTempDir = new File(PathManager.getTempPath());
    if (!(ideTempDir.mkdirs() || ideTempDir.exists())) {
      throw new RuntimeException("Unable to create temp directory '" + ideTempDir + "'");
    }
    if (System.getProperty("jna.tmpdir") == null) {
      System.setProperty("jna.tmpdir", ideTempDir.getPath());
    }
    if (System.getProperty("jna.nosys") == null) {
      System.setProperty("jna.nosys", "true");  // prefer bundled JNA dispatcher lib
    }
    JnaLoader.load(log);

    if (SystemInfo.isWin2kOrNewer) {
      //noinspection ResultOfMethodCallIgnored
      IdeaWin32.isAvailable();  // logging is done there
    }

    if (SystemInfo.isWindows) {
      // WinP should not unpack .dll files into parent directory
      System.setProperty("winp.unpack.dll.to.parent.dir", "false");
    }
  }

  private static void startLogging(final Logger log) {
    ShutDownTracker.getInstance().registerShutdownTask(() ->
        log.info("------------------------------------------------------ IDE SHUTDOWN ------------------------------------------------------"));
    log.info("------------------------------------------------------ IDE STARTED ------------------------------------------------------");

    ApplicationInfo appInfo = ApplicationInfoImpl.getShadowInstance();
    ApplicationNamesInfo namesInfo = ApplicationNamesInfo.getInstance();
    String buildDate = new SimpleDateFormat("dd MMM yyyy HH:ss", Locale.US).format(appInfo.getBuildDate().getTime());
    log.info("IDE: " + namesInfo.getFullProductName() + " (build #" + appInfo.getBuild().asString() + ", " + buildDate + ")");
    log.info("OS: " + SystemInfoRt.OS_NAME + " (" + SystemInfoRt.OS_VERSION + ", " + SystemInfo.OS_ARCH + ")");
    log.info("JRE: " + System.getProperty("java.runtime.version", "-") + " (" + System.getProperty("java.vendor", "-") + ")");
    log.info("JVM: " + System.getProperty("java.vm.version", "-") + " (" + System.getProperty("java.vm.name", "-") + ")");

    List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (arguments != null) {
      log.info("JVM Args: " + StringUtil.join(arguments, " "));
    }

    String extDirs = System.getProperty("java.ext.dirs");
    if (extDirs != null) {
      for (String dir : StringUtil.split(extDirs, File.pathSeparator)) {
        String[] content = new File(dir).list();
        if (content != null && content.length > 0) {
          log.info("ext: " + dir + ": " + Arrays.toString(content));
        }
      }
    }

    log.info("charsets: JNU=" + System.getProperty("sun.jnu.encoding") + " file=" + System.getProperty("file.encoding"));
  }

  private static void installPluginUpdates() {
    if (!Main.isCommandLine() && !ClassUtilCore.isLoadingOfExternalPluginsDisabled()) {
      try {
        StartupActionScriptManager.executeActionScript();
      }
      catch (IOException e) {
        String message =
          "The IDE failed to install some plugins.\n\n" +
          "Most probably, this happened because of a change in a serialization format.\n" +
          "Please try again, and if the problem persists, please report it\n" +
          "to http://jb.gg/ide/critical-startup-errors" +
          "\n\nThe cause: " + e.getMessage();
        Main.showMessage("Plugin Installation Error", message, false);
      }
    }
  }

  static void runStartupWizard() {
    ApplicationInfoEx appInfo = ApplicationInfoImpl.getShadowInstance();

    String stepsProviderName = appInfo.getCustomizeIDEWizardStepsProvider();
    if (stepsProviderName != null) {
      CustomizeIDEWizardStepsProvider provider;

      try {
        Class<?> providerClass = Class.forName(stepsProviderName);
        provider = (CustomizeIDEWizardStepsProvider)providerClass.newInstance();
      }
      catch (Throwable e) {
        Main.showMessage("Configuration Wizard Failed", e);
        return;
      }

      CloudConfigProvider configProvider = CloudConfigProvider.getProvider();
      if (configProvider != null) {
        configProvider.beforeStartupWizard();
      }

      new CustomizeIDEWizardDialog(provider).show();

      PluginManagerCore.invalidatePlugins();
      if (configProvider != null) {
        configProvider.startupWizardFinished();
      }

      return;
    }

    List<ApplicationInfoEx.PluginChooserPage> pages = appInfo.getPluginChooserPages();
    if (!pages.isEmpty()) {
      new StartupWizard(pages).show();
      PluginManagerCore.invalidatePlugins();
    }
  }
}