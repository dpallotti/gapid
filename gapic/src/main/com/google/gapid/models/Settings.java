/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gapid.models;

import static java.util.Arrays.stream;
import static java.util.logging.Level.FINE;
import static java.util.stream.Collectors.joining;
import static java.util.stream.StreamSupport.stream;

import com.google.common.base.Splitter;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gapid.server.Client;
import com.google.gapid.server.GapiPaths;
import com.google.gapid.util.OS;

import org.eclipse.swt.graphics.Point;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Stores various settings based on user interactions with the UI to maintain customized looks and
 * other shortcuts between runs. E.g. size and location of the window, directory of the last opened
 * file, options for the last trace, etc. The settings are persisted in a ".gapic" file in the
 * user's home directory.
 */
public class Settings {
  private static final Logger LOG = Logger.getLogger(Settings.class.getName());
  private static final String SETTINGS_FILE = ".gapic";
  private static final int MAX_RECENT_FILES = 16;

  public Point windowLocation = null;
  public Point windowSize = null;
  public int[] tabWeights = new int[] { 1, 1, 4, 1, 3, 1 };
  public String tabStructure = "g2;f1;g3;f1;f1;f2"; // See GraphicsTraceView.MainTab.getFolders().
  public String[] tabs = new String[] { "Filmstrip", "ApiCalls", "Framebuffer", "ApiState", "Memory" };
  public String[] hiddenTabs = new String[] { "Log" };
  public String lastOpenDir = "";
  public int[] programTreeSplitterWeights = new int[] { 20, 80 };
  public int[] programUniformsSplitterWeights = new int[] { 70, 30 };
  public int[] reportSplitterWeights = new int[] { 75, 25 };
  public int[] shaderTreeSplitterWeights = new int[] { 20, 80 };
  public int[] texturesSplitterWeights = new int[] { 20, 80 };
  public String traceDeviceSerial = "";
  public String traceDeviceName = "";
  public String traceType = "Graphics";
  public String traceApi = "";
  public String traceUri = "";
  public String traceArguments = "";
  public String traceCwd = "";
  public String traceEnv = "";
  public int traceStartAt = -1; // -1: manual (mec), 0: beginning, 1+ frame x
  public int traceDuration = 7; // 0: manual, 1+ frames/seconds
  public boolean traceWithoutBuffering = false;
  public boolean traceHideUnknownExtensions = false;
  public boolean traceClearCache = false;
  public boolean traceDisablePcs = true;
  public String traceOutDir = "";
  public String traceFriendlyName = "";
  public boolean skipFirstRunDialog = false;
  public String[] recentFiles = new String[0];
  public String adb = "";
  public boolean autoCheckForUpdates = true;
  public boolean includeDevReleases = false;
  public boolean updateAvailable = false;
  public long lastCheckForUpdates = 0; // milliseconds since midnight, January 1, 1970 UTC.
  public String analyticsClientId = ""; // Empty means do not track.
  public boolean disableReplayOptimization = false;
  public boolean reportCrashes = false;
  public int perfettoDrawerHeight = 250;
  public boolean perfettoDarkMode = false;
  public boolean perfettoCpu = true;
  public boolean perfettoCpuFreq = false;
  public boolean perfettoCpuChain = true;
  public boolean perfettoCpuSlices = true;
  public boolean perfettoGpu = true;
  public boolean perfettoGpuSlices = true;
  public boolean perfettoGpuCounters = true;
  public int perfettoGpuCounterRate = 50;
  public int[] perfettoGpuCounterIds = new int[0];
  public boolean perfettoMem = true;
  public int perfettoMemRate = 10;
  public boolean perfettoBattery = true;
  public int perfettoBatteryRate = 250;
  public boolean perfettoVulkanCPUTiming = false;
  public boolean perfettoVulkanCPUTimingCommandBuffer = false;
  public boolean perfettoVulkanCPUTimingDevice = false;
  public boolean perfettoVulkanCPUTimingInstance = false;
  public boolean perfettoVulkanCPUTimingPhysicalDevice = false;
  public boolean perfettoVulkanCPUTimingQueue = false;
  public boolean perfettoVulkanMemoryTracker = false;

  public static Settings load() {
    Settings result = new Settings();

    File file = new File(OS.userHomeDir, SETTINGS_FILE);
    if (file.exists() && file.canRead()) {
      try (Reader reader = new FileReader(file)) {
        Properties properties = new Properties();
        properties.load(reader);
        result.updateFrom(properties);
      } catch (IOException e) {
        LOG.log(FINE, "IO error reading properties from " + file, e);
      }
    }

    return result;
  }

  public void save() {
    File file = new File(OS.userHomeDir, SETTINGS_FILE);
    try (Writer writer = new FileWriter(file)) {
      Properties properties = new Properties();
      updateTo(properties);
      properties.store(writer, " GAPIC Properties");
    } catch (IOException e) {
      LOG.log(FINE, "IO error writing properties to " + file, e);
    }
  }

  public ListenableFuture<Void> updateOnServer(Client client) {
    return client.updateSettings(reportCrashes, analyticsEnabled(), analyticsClientId, adb);
  }

  public void addToRecent(String file) {
    for (int i = 0; i < recentFiles.length; i++) {
      if (file.equals(recentFiles[i])) {
        if (i != 0) {
          // Move to front.
          System.arraycopy(recentFiles, 0, recentFiles, 1, i);
          recentFiles[0] = file;
        }
        return;
      }
    }

    // Not found.
    if (recentFiles.length >= MAX_RECENT_FILES) {
      String[] tmp = new String[MAX_RECENT_FILES];
      System.arraycopy(recentFiles, 0, tmp, 1, MAX_RECENT_FILES - 1);
      recentFiles = tmp;
    } else {
      String[] tmp = new String[recentFiles.length + 1];
      System.arraycopy(recentFiles, 0, tmp, 1, recentFiles.length);
      recentFiles = tmp;
    }
    recentFiles[0] = file;
  }

  public String[] getRecent() {
    return stream(recentFiles)
        .map(file -> new File(file))
        .filter(File::exists)
        .filter(File::canRead)
        .map(File::getAbsolutePath)
        .toArray(String[]::new);
  }

  public boolean analyticsEnabled() {
    return !analyticsClientId.isEmpty();
  }

  public void setAnalyticsEnabled(boolean enabled) {
    if (enabled && !analyticsEnabled()) {
      analyticsClientId = UUID.randomUUID().toString();
    } else if (!enabled && analyticsEnabled()) {
      analyticsClientId = "";
    }
  }

  public boolean isAdbValid() {
    return checkAdbIsValid(adb) == null;
  }

  /**
   * Returns an error message if adb is invalid or {@code null} if it is valid.
   */
  public static String checkAdbIsValid(String adb) {
    if (adb.isEmpty()) {
      return "path is missing";
    }

    try {
      Path path = FileSystems.getDefault().getPath(adb);
      if (!Files.exists(path)) {
        return "path does not exist";
      } else if (!Files.isRegularFile(path)) {
        return "path is not a file";
      } else if (!Files.isExecutable(path)) {
        return "path is not an executable";
      }
    } catch (InvalidPathException e) {
      return "path is invalid: " + e.getReason();
    }

    return null;
  }

  private void updateFrom(Properties properties) {
    windowLocation = getPoint(properties, "window.pos");
    windowSize = getPoint(properties, "window.size");
    tabs = getStringList(properties, "tabs", tabs);
    tabWeights = getIntList(properties, "tabs.iweights", tabWeights);
    tabStructure = properties.getProperty("tabs.structure", tabStructure);
    hiddenTabs = getStringList(properties, "tabs.hidden", hiddenTabs);
    lastOpenDir = properties.getProperty("lastOpenDir", lastOpenDir);
    programTreeSplitterWeights =
        getIntList(properties, "programTree.splitter.weights", programTreeSplitterWeights);
    programUniformsSplitterWeights =
        getIntList(properties, "programUniforms.splitter.weights", programUniformsSplitterWeights);
    reportSplitterWeights =
        getIntList(properties, "report.splitter.weights", reportSplitterWeights);
    shaderTreeSplitterWeights =
        getIntList(properties, "shaderTree.splitter.weights", shaderTreeSplitterWeights);
    texturesSplitterWeights =
        getIntList(properties, "texture.splitter.weights", texturesSplitterWeights);
    traceDeviceSerial = properties.getProperty("trace.device.serial", traceDeviceSerial);
    traceDeviceName = properties.getProperty("trace.device.name", traceDeviceName);
    traceType = properties.getProperty("trace.type", traceType);
    traceApi = properties.getProperty("trace.api", traceApi);
    traceUri = properties.getProperty("trace.uri", traceUri);
    traceArguments = properties.getProperty("trace.arguments", traceArguments);
    traceCwd = properties.getProperty("trace.cwd", traceCwd);
    traceEnv = properties.getProperty("trace.env", traceEnv);
    traceStartAt = getInt(properties, "trace.startAt", traceStartAt);
    traceDuration = getInt(properties, "trace.duration", traceDuration);
    traceWithoutBuffering = getBoolean(properties, "trace.withoutBuffering", traceWithoutBuffering);
    traceHideUnknownExtensions = getBoolean(properties, "trace.hideUnknownExtensions", traceHideUnknownExtensions);
    traceClearCache = getBoolean(properties, "trace.clearCache", traceClearCache);
    traceDisablePcs = getBoolean(properties, "trace.disablePCS", traceDisablePcs);
    traceOutDir = properties.getProperty("trace.dir", traceOutDir);
    traceFriendlyName = properties.getProperty("trace.friendly", traceFriendlyName);
    skipFirstRunDialog = getBoolean(properties, "skip.firstTime", skipFirstRunDialog);
    recentFiles = getStringList(properties, "open.recent", recentFiles);
    adb = tryFindAdb(properties.getProperty("adb.path", ""));
    autoCheckForUpdates = getBoolean(properties, "updates.autoCheck", autoCheckForUpdates);
    includeDevReleases = getBoolean(properties, "updates.includeDevReleases", includeDevReleases);
    lastCheckForUpdates = getLong(properties, "updates.lastCheck", 0);
    updateAvailable = getBoolean(properties, "updates.available", updateAvailable);
    analyticsClientId = properties.getProperty("analytics.clientId", "");
    disableReplayOptimization =
        getBoolean(properties, "replay.disableOptimization", disableReplayOptimization);
    reportCrashes = getBoolean(properties, "crash.reporting", reportCrashes);
    perfettoDrawerHeight = getInt(properties, "perfetto.drawer.height", perfettoDrawerHeight);
    perfettoDarkMode = getBoolean(properties, "perfetto.dark", perfettoDarkMode);
    perfettoCpu = getBoolean(properties, "perfetto.cpu", perfettoCpu);
    perfettoCpuFreq = getBoolean(properties, "perfetto.cpu.freq", perfettoCpuFreq);
    perfettoCpuChain = getBoolean(properties, "perfetto.cpu.chain", perfettoCpuChain);
    perfettoCpuSlices = getBoolean(properties, "perfetto.cpu.slices", perfettoCpuSlices);
    perfettoGpu = getBoolean(properties, "perfetto.gpu", perfettoGpu);
    perfettoGpuSlices = getBoolean(properties, "perfetto.gpu.slices", perfettoGpuSlices);
    perfettoGpuCounters = getBoolean(properties, "perfetto.gpu.counters", perfettoGpuCounters);
    perfettoGpuCounterRate = getInt(properties, "perfetto.gpu.counters.rate", perfettoGpuCounterRate);
    perfettoGpuCounterIds = getIntList(properties, "perfetto.gpu.counters.ids", perfettoGpuCounterIds);
    perfettoMem = getBoolean(properties, "perfetto.mem", perfettoMem);
    perfettoMemRate = getInt(properties, "perfetto.mem.rate", perfettoMemRate);
    perfettoBattery = getBoolean(properties, "perfetto.battery", perfettoBattery);
    perfettoBatteryRate = getInt(properties, "perfetto.battery.rate", perfettoBatteryRate);
    perfettoVulkanCPUTiming = getBoolean(properties, "perfetto.vulkan.cpu_timing", perfettoVulkanCPUTiming);
    perfettoVulkanCPUTimingCommandBuffer = getBoolean(properties, "perfetto.vulkan.cpu_timing.command_buffer", perfettoVulkanCPUTimingCommandBuffer);
    perfettoVulkanCPUTimingDevice = getBoolean(properties, "perfetto.vulkan.cpu_timing.device", perfettoVulkanCPUTimingDevice);
    perfettoVulkanCPUTimingInstance = getBoolean(properties, "perfetto.vulkan.cpu_timing.instance", perfettoVulkanCPUTimingInstance);
    perfettoVulkanCPUTimingQueue = getBoolean(properties, "perfetto.vulkan.cpu_timing.queue", perfettoVulkanCPUTimingQueue);
    perfettoVulkanCPUTimingPhysicalDevice = getBoolean(properties, "perfetto.vulkan.cpu_timing.physical_device", perfettoVulkanCPUTimingPhysicalDevice);
    perfettoVulkanMemoryTracker =
        getBoolean(properties, "perfetto.vulkan.memory_tracker", perfettoVulkanMemoryTracker);
  }

  private void updateTo(Properties properties) {
    setPoint(properties, "window.pos", windowLocation);
    setPoint(properties, "window.size", windowSize);
    setStringList(properties, "tabs", tabs);
    setIntList(properties, "tabs.iweights", tabWeights);
    properties.setProperty("tabs.structure", tabStructure);
    setStringList(properties, "tabs.hidden", hiddenTabs);
    properties.setProperty("lastOpenDir", lastOpenDir);
    setIntList(properties, "programTree.splitter.weights", programTreeSplitterWeights);
    setIntList(properties, "programUniforms.splitter.weights", programUniformsSplitterWeights);
    setIntList(properties, "report.splitter.weights", reportSplitterWeights);
    setIntList(properties, "shaderTree.splitter.weights", shaderTreeSplitterWeights);
    setIntList(properties, "texture.splitter.weights", texturesSplitterWeights);
    properties.setProperty("trace.device.serial", traceDeviceSerial);
    properties.setProperty("trace.device.name", traceDeviceName);
    properties.setProperty("trace.type", traceType);
    properties.setProperty("trace.api", traceApi);
    properties.setProperty("trace.uri", traceUri);
    properties.setProperty("trace.arguments", traceArguments);
    properties.setProperty("trace.cwd", traceCwd);
    properties.setProperty("trace.env", traceEnv);
    properties.setProperty("trace.startAt", Integer.toString(traceStartAt));
    properties.setProperty("trace.duration", Integer.toString(traceDuration));
    properties.setProperty("trace.withoutBuffering", Boolean.toString(traceWithoutBuffering));
    properties.setProperty("trace.hideUnknownExtensions", Boolean.toString(traceHideUnknownExtensions));
    properties.setProperty("trace.clearCache", Boolean.toString(traceClearCache));
    properties.setProperty("trace.disablePCS", Boolean.toString(traceDisablePcs));
    properties.setProperty("trace.dir", traceOutDir);
    properties.setProperty("trace.friendly",  traceFriendlyName);
    properties.setProperty("skip.firstTime", Boolean.toString(skipFirstRunDialog));
    setStringList(properties, "open.recent", recentFiles);
    properties.setProperty("adb.path", adb);
    properties.setProperty("updates.autoCheck", Boolean.toString(autoCheckForUpdates));
    properties.setProperty("updates.includeDevReleases", Boolean.toString(includeDevReleases));
    properties.setProperty("updates.lastCheck", Long.toString(lastCheckForUpdates));
    properties.setProperty("updates.available", Boolean.toString(updateAvailable));
    properties.setProperty("analytics.clientId", analyticsClientId);
    properties.setProperty(
        "replay.disableOptimization",  Boolean.toString(disableReplayOptimization));
    properties.setProperty("crash.reporting", Boolean.toString(reportCrashes));
    properties.setProperty("perfetto.drawer.height", Integer.toString(perfettoDrawerHeight));
    properties.setProperty("perfetto.dark", Boolean.toString(perfettoDarkMode));
    properties.setProperty("perfetto.cpu", Boolean.toString(perfettoCpu));
    properties.setProperty("perfetto.cpu.freq", Boolean.toString(perfettoCpuFreq));
    properties.setProperty("perfetto.cpu.chain", Boolean.toString(perfettoCpuChain));
    properties.setProperty("perfetto.cpu.slices", Boolean.toString(perfettoCpuSlices));
    properties.setProperty("perfetto.gpu", Boolean.toString(perfettoGpu));
    properties.setProperty("perfetto.gpu.slices", Boolean.toString(perfettoGpuSlices));
    properties.setProperty("perfetto.gpu.counters", Boolean.toString(perfettoGpuCounters));
    properties.setProperty("perfetto.gpu.counters.rate", Integer.toString(perfettoGpuCounterRate));
    setIntList(properties, "perfetto.gpu.counters.ids", perfettoGpuCounterIds);
    properties.setProperty("perfetto.mem", Boolean.toString(perfettoMem));
    properties.setProperty("perfetto.mem.rate", Integer.toString(perfettoMemRate));
    properties.setProperty("perfetto.battery", Boolean.toString(perfettoBattery));
    properties.setProperty("perfetto.battery.rate", Integer.toString(perfettoBatteryRate));
    properties.setProperty("perfetto.vulkan.cpu_timing", Boolean.toString(perfettoVulkanCPUTiming));
    properties.setProperty("perfetto.vulkan.cpu_timing.command_buffer", Boolean.toString(perfettoVulkanCPUTimingCommandBuffer));
    properties.setProperty("perfetto.vulkan.cpu_timing.device", Boolean.toString(perfettoVulkanCPUTimingDevice));
    properties.setProperty("perfetto.vulkan.cpu_timing.instance", Boolean.toString(perfettoVulkanCPUTimingInstance));
    properties.setProperty("perfetto.vulkan.cpu_timing.queue", Boolean.toString(perfettoVulkanCPUTimingQueue));
    properties.setProperty("perfetto.vulkan.cpu_timing.physical_device", Boolean.toString(perfettoVulkanCPUTimingPhysicalDevice));

    properties.setProperty(
        "perfetto.vulkan.memory_tracker", Boolean.toString(perfettoVulkanMemoryTracker));
  }

  private static Point getPoint(Properties properties, String name) {
    int x = getInt(properties, name + ".x", -1), y = getInt(properties, name + ".y", -1);
    return (x >= 0 && y >= 0) ? new Point(x, y) : null;
  }

  private static int getInt(Properties properties, String name, int dflt) {
    String value = properties.getProperty(name);
    if (value == null) {
      return dflt;
    }

    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static long getLong(Properties properties, String name, long dflt) {
    String value = properties.getProperty(name);
    if (value == null) {
      return dflt;
    }

    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static boolean getBoolean(Properties properties, String name, boolean dflt) {
    if (dflt) {
      return !"false".equalsIgnoreCase(properties.getProperty(name));
    } else {
      return "true".equalsIgnoreCase(properties.getProperty(name));
    }
  }

  private static int[] getIntList(Properties properties, String name, int[] dflt) {
    String value = properties.getProperty(name);
    if (value == null) {
      return dflt;
    }

    try {
      return stream(Splitter.on(',').split(value).spliterator(), false)
          .mapToInt(Integer::parseInt)
          .toArray();
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static String[] getStringList(Properties properties, String name, String[] dflt) {
    String value = properties.getProperty(name);
    if (value == null) {
      return dflt;
    }
    return stream(
        Splitter.on(',').trimResults().omitEmptyStrings().split(value).spliterator(), false)
        .toArray(String[]::new);
  }

  private static void setPoint(Properties properties, String name, Point point) {
    if (point != null) {
      properties.setProperty(name + ".x", Integer.toString(point.x));
      properties.setProperty(name + ".y", Integer.toString(point.y));
    }
  }

  private static void setIntList(Properties properties, String name, int[] value) {
    properties.setProperty(name, stream(value).mapToObj(String::valueOf).collect(joining(",")));
  }

  private static void setStringList(Properties properties, String name, String[] value) {
    properties.setProperty(name, stream(value).collect(joining(",")));
  }

  private static String tryFindAdb(String current) {
    if (!current.isEmpty()) {
      return current;
    }

    String[] sdkVars = { "ANDROID_HOME", "ANDROID_SDK_HOME", "ANDROID_ROOT", "ANDROID_SDK_ROOT" };
    for (String sdkVar : sdkVars) {
      File adb = findAdbInSdk(System.getenv(sdkVar));
      if (adb != null) {
        return adb.getAbsolutePath();
      }
    }

    // If not found, but the flag is specified, use that.
    return GapiPaths.adbPath.get();
  }

  private static File findAdbInSdk(String sdk) {
    if (sdk == null || sdk.isEmpty()) {
      return null;
    }
    File adb = new File(sdk, "platform-tools" + File.separator + "adb" + OS.exeExtension);
    return (adb.exists() && adb.canExecute()) ? adb : null;
  }
}
