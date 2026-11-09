import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { PermissionRow } from './src/components/PermissionRow';
import {
  ARController,
  checkRuntime,
  requestAllRuntime,
  requestRuntime,
  type AIConfig,
  type NativeStatus,
  type RuntimeKey,
} from './src/native/ARController';
import { colors } from './src/theme';

type Status = NativeStatus & Record<RuntimeKey, boolean>;

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={styles.safe}>
        <Home />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function Home() {
  const [status, setStatus] = useState<Status | null>(null);
  const [config, setConfig] = useState<AIConfig | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [testText, setTestText] = useState('');
  const [testReply, setTestReply] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const [nativeStatus, runtime] = await Promise.all([ARController.getStatus(), checkRuntime()]);
    setStatus({ ...nativeStatus, ...runtime });
  }, []);

  useEffect(() => {
    refresh();
    ARController.getConfig().then(setConfig);
    // Re-check when the user comes back from a Settings screen.
    const sub = AppState.addEventListener('change', s => s === 'active' && refresh());
    return () => sub.remove();
  }, [refresh]);

  if (!status || !config) {
    return <ActivityIndicator style={styles.loader} color={colors.primary} />;
  }

  const coreReady = status.overlay && status.microphone;

  const toggleController = async () => {
    try {
      if (status.serviceRunning) ARController.stopController();
      else await ARController.startController();
    } catch (e: any) {
      Alert.alert('AR Controller', e?.message ?? String(e));
    }
    setTimeout(refresh, 400);
  };

  const saveConfig = async () => {
    await ARController.saveConfig({
      apiKey: apiKey || undefined,
      baseUrl: config.baseUrl,
      model: config.model,
      language: config.language,
      wakeWordEnabled: config.wakeWordEnabled,
    });
    setApiKey('');
    setConfig(await ARController.getConfig());
    Alert.alert('Saved', 'AI settings saved.');
  };

  const runTest = async () => {
    if (!testText.trim()) return;
    setBusy(true);
    try {
      const r = await ARController.runCommand(testText.trim());
      setTestReply(`[${r.action}${r.target ? ` → ${r.target}` : ''}]  ${r.reply}`);
    } catch (e: any) {
      setTestReply(`Error: ${e?.message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  const ask = (key: RuntimeKey) => requestRuntime(key).then(refresh);

  return (
    <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
      <View style={styles.hero}>
        <View style={styles.logo}>
          <Text style={styles.logoText}>AR</Text>
        </View>
        <Text style={styles.h1}>AR Controller</Text>
        <Text style={styles.sub}>
          Floating AI assistant — bolo “Hey AR”, apps floating window mein kholo, call karo, phone control karo.
        </Text>
      </View>

      {/* 1. Permissions */}
      <View style={styles.card}>
        <View style={styles.cardHeader}>
          <Text style={styles.h2}>1. Permissions</Text>
          <Pressable onPress={() => requestAllRuntime().then(refresh)}>
            <Text style={styles.link}>Allow all</Text>
          </Pressable>
        </View>
        <PermissionRow
          icon="🪟"
          title="Display over other apps"
          description="Floating icon & control panel ke liye"
          granted={status.overlay}
          actionLabel="Open"
          onPress={ARController.openOverlaySettings}
        />
        <PermissionRow
          icon="🎙"
          title="Microphone"
          description="“Hey AR” voice commands"
          granted={status.microphone}
          onPress={() => ask('microphone')}
        />
        <PermissionRow
          icon="♿"
          title="Accessibility service"
          description="Home/Back/Recents, lock, screenshot, split screen, click & type"
          granted={status.accessibility}
          actionLabel="Open"
          onPress={ARController.openAccessibilitySettings}
        />
        <PermissionRow
          icon="👥"
          title="Contacts"
          description="“Ali ko call karo” — naam se number dhoondna"
          granted={status.contacts}
          onPress={() => ask('contacts')}
        />
        <PermissionRow
          icon="📞"
          title="Phone calls"
          description="Seedha call lagana (warna dialer khulega)"
          granted={status.phone}
          onPress={() => ask('phone')}
        />
        <PermissionRow
          icon="🔔"
          title="Notifications"
          description="Background mein chalte rehne ka notification"
          granted={status.notifications}
          onPress={() => ask('notifications')}
        />
        <PermissionRow
          icon="🧩"
          title="Freeform windows"
          optional
          description="Apps ko beech mein floating box mein kholne ke liye. Developer options → “Enable freeform windows” + “Force activities to be resizable”, phir restart."
          granted={status.freeform}
          actionLabel="Dev opts"
          onPress={ARController.openDeveloperOptions}
        />
        {!status.accessibility && status.sdk >= 33 ? (
          <Pressable onPress={ARController.openAppDetails}>
            <Text style={styles.hint}>
              Accessibility “Restricted setting” dikha raha hai? Yahan tap karein → ⋮ menu → “Allow restricted
              settings”, phir dobara Accessibility on karein.
            </Text>
          </Pressable>
        ) : null}
      </View>

      {/* 2. AI */}
      <View style={styles.card}>
        <Text style={styles.h2}>2. AI brain</Text>
        <Text style={styles.small}>
          Koi bhi OpenAI-compatible API (OpenAI, Groq, OpenRouter, local LLM). Key ke baghair bhi basic commands offline
          chalti hain.
        </Text>
        <Text style={styles.label}>API key {config.hasApiKey ? '(saved ✓)' : ''}</Text>
        <TextInput
          style={styles.input}
          value={apiKey}
          onChangeText={setApiKey}
          placeholder={config.hasApiKey ? '•••••••• (leave empty to keep)' : 'sk-…'}
          placeholderTextColor={colors.muted}
          secureTextEntry
          autoCapitalize="none"
        />
        <Text style={styles.label}>Base URL</Text>
        <TextInput
          style={styles.input}
          value={config.baseUrl}
          onChangeText={baseUrl => setConfig({ ...config, baseUrl })}
          autoCapitalize="none"
          placeholderTextColor={colors.muted}
        />
        <View style={styles.rowInputs}>
          <View style={styles.modelCol}>
            <Text style={styles.label}>Model</Text>
            <TextInput
              style={styles.input}
              value={config.model}
              onChangeText={model => setConfig({ ...config, model })}
              autoCapitalize="none"
            />
          </View>
          <View style={styles.langCol}>
            <Text style={styles.label}>Voice lang</Text>
            <TextInput
              style={styles.input}
              value={config.language}
              onChangeText={language => setConfig({ ...config, language })}
              autoCapitalize="none"
              placeholder="en-IN / ur-PK"
              placeholderTextColor={colors.muted}
            />
          </View>
        </View>
        <View style={styles.switchRow}>
          <Text style={styles.switchLabel}>Always listen for “Hey AR”</Text>
          <Switch
            value={config.wakeWordEnabled}
            onValueChange={wakeWordEnabled => setConfig({ ...config, wakeWordEnabled })}
            trackColor={{ true: colors.primary, false: colors.border }}
          />
        </View>
        <Pressable style={[styles.btn, { backgroundColor: colors.blue }]} onPress={saveConfig}>
          <Text style={styles.btnText}>Save AI settings</Text>
        </Pressable>
      </View>

      {/* 3. Start */}
      <View style={styles.card}>
        <Text style={styles.h2}>3. Floating controller</Text>
        {!coreReady ? (
          <Text style={styles.warn}>Pehle “Display over other apps” aur “Microphone” allow karein.</Text>
        ) : null}
        <Pressable
          disabled={!status.overlay}
          style={[
            styles.btn,
            { backgroundColor: status.serviceRunning ? colors.danger : colors.primary },
            !status.overlay && styles.disabled,
          ]}
          onPress={toggleController}>
          <Text style={styles.btnText}>
            {status.serviceRunning ? 'Stop floating AR' : 'Start floating AR'}
          </Text>
        </Pressable>
        <Text style={styles.small}>
          Start ke baad screen par “AR” icon aayega. Tap → Control Panel → ALL APPS → kisi app par tap = floating
          window. Kahin bhi bolo “Hey AR, YouTube kholo”.
        </Text>
      </View>

      {/* 4. Test */}
      <View style={styles.card}>
        <Text style={styles.h2}>4. Test a command</Text>
        <TextInput
          style={styles.input}
          value={testText}
          onChangeText={setTestText}
          placeholder="e.g. float WhatsApp / torch on / Ali ko call karo"
          placeholderTextColor={colors.muted}
          onSubmitEditing={runTest}
        />
        <Pressable style={[styles.btn, { backgroundColor: colors.ok }]} onPress={runTest} disabled={busy}>
          {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.btnText}>Run</Text>}
        </Pressable>
        {testReply ? <Text style={styles.reply}>{testReply}</Text> : null}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  scroll: { padding: 16, paddingBottom: 48 },
  hero: { alignItems: 'center', marginVertical: 12 },
  logo: {
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderColor: '#fff',
  },
  logoText: { color: '#fff', fontSize: 26, fontWeight: '800' },
  h1: { color: colors.text, fontSize: 26, fontWeight: '800', marginTop: 10 },
  sub: { color: colors.muted, textAlign: 'center', marginTop: 6, fontSize: 13, lineHeight: 19 },
  card: {
    backgroundColor: colors.card,
    borderRadius: 18,
    padding: 16,
    marginTop: 14,
    borderWidth: 1,
    borderColor: colors.border,
  },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  h2: { color: colors.text, fontSize: 17, fontWeight: '700', marginBottom: 6 },
  link: { color: '#A5B4FC', fontWeight: '700' },
  small: { color: colors.muted, fontSize: 12, lineHeight: 18, marginTop: 8 },
  hint: { color: colors.warn, fontSize: 12, marginTop: 10, lineHeight: 18 },
  warn: { color: colors.warn, fontSize: 13, marginBottom: 8 },
  label: { color: colors.muted, fontSize: 12, marginTop: 10, marginBottom: 4 },
  input: {
    backgroundColor: colors.bg,
    color: colors.text,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  rowInputs: { flexDirection: 'row' },
  modelCol: { flex: 1, marginRight: 8 },
  langCol: { width: 110 },
  loader: { flex: 1 },
  disabled: { opacity: 0.4 },
  switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 12 },
  switchLabel: { color: colors.text, fontSize: 14 },
  btn: { borderRadius: 12, paddingVertical: 13, alignItems: 'center', marginTop: 12 },
  btnText: { color: '#fff', fontWeight: '800', fontSize: 15 },
  reply: { color: colors.text, marginTop: 10, fontSize: 13, lineHeight: 19 },
});
