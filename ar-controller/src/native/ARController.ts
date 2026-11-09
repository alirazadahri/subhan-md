import { NativeModules, PermissionsAndroid, Platform, type Permission } from 'react-native';

export type NativeStatus = {
  overlay: boolean;
  accessibility: boolean;
  accessibilityConnected: boolean;
  freeform: boolean;
  devOptionsEnabled: boolean;
  canWriteSecureSettings: boolean;
  serviceRunning: boolean;
  sdk: number;
};

export type AIConfig = {
  hasApiKey: boolean;
  baseUrl: string;
  model: string;
  language: string;
  wakeWordEnabled: boolean;
  freeAiEnabled: boolean;
};

export type SaveConfig = Partial<Omit<AIConfig, 'hasApiKey'>> & { apiKey?: string };

export type CommandResult = { action: string; target?: string | null; reply: string };

type ARControllerNative = {
  getStatus(): Promise<NativeStatus>;
  openOverlaySettings(): void;
  openAccessibilitySettings(): void;
  openAppDetails(): void;
  /** Resolves "developer" when Developer options opened, or "about" when they're still hidden. */
  openDeveloperOptions(): Promise<'developer' | 'about'>;
  /** Needs the one-time ADB grant of WRITE_SECURE_SETTINGS; resolves false otherwise. */
  enableFreeform(): Promise<boolean>;
  startController(): Promise<boolean>;
  stopController(): void;
  getConfig(): Promise<AIConfig>;
  saveConfig(config: SaveConfig): Promise<boolean>;
  clearApiKey(): void;
  runCommand(text: string): Promise<CommandResult>;
};

const native: ARControllerNative | undefined = NativeModules.ARController;

if (!native && Platform.OS === 'android') {
  console.warn('ARController native module missing — did you add ARControllerPackage to MainApplication.kt?');
}

const n = native as ARControllerNative;

// Every wrapper drops extra arguments: passing a native method straight to onPress forwards the press
// event, and an argument-count mismatch on a native method is a fatal error that closes the app.
export const ARController = {
  getStatus: () => n.getStatus(),
  openOverlaySettings: () => n.openOverlaySettings(),
  openAccessibilitySettings: () => n.openAccessibilitySettings(),
  openAppDetails: () => n.openAppDetails(),
  openDeveloperOptions: () => n.openDeveloperOptions(),
  enableFreeform: () => n.enableFreeform(),
  startController: () => n.startController(),
  stopController: () => n.stopController(),
  getConfig: () => n.getConfig(),
  saveConfig: (config: SaveConfig) => n.saveConfig(config),
  clearApiKey: () => n.clearApiKey(),
  runCommand: (text: string) => n.runCommand(text),
};

/** Runtime ("dangerous") permissions requested with the normal Android dialog. */
export const RUNTIME_PERMISSIONS = {
  microphone: PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
  contacts: PermissionsAndroid.PERMISSIONS.READ_CONTACTS,
  phone: PermissionsAndroid.PERMISSIONS.CALL_PHONE,
  notifications: PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
} as const;

export type RuntimeKey = keyof typeof RUNTIME_PERMISSIONS;

export async function checkRuntime(): Promise<Record<RuntimeKey, boolean>> {
  const entries = await Promise.all(
    (Object.keys(RUNTIME_PERMISSIONS) as RuntimeKey[]).map(async key => {
      // POST_NOTIFICATIONS only exists on Android 13+; older versions grant it implicitly.
      if (key === 'notifications' && Number(Platform.Version) < 33) return [key, true] as const;
      return [key, await PermissionsAndroid.check(RUNTIME_PERMISSIONS[key] as Permission)] as const;
    }),
  );
  return Object.fromEntries(entries) as Record<RuntimeKey, boolean>;
}

export async function requestAllRuntime(): Promise<void> {
  const perms = (Object.keys(RUNTIME_PERMISSIONS) as RuntimeKey[])
    .filter(k => !(k === 'notifications' && Number(Platform.Version) < 33))
    .map(k => RUNTIME_PERMISSIONS[k] as Permission);
  await PermissionsAndroid.requestMultiple(perms);
}

export async function requestRuntime(key: RuntimeKey): Promise<boolean> {
  const res = await PermissionsAndroid.request(RUNTIME_PERMISSIONS[key] as Permission);
  return res === PermissionsAndroid.RESULTS.GRANTED;
}
