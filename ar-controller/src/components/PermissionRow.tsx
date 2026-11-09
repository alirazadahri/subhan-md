import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme';

type Props = {
  icon: string;
  title: string;
  description: string;
  granted: boolean;
  optional?: boolean;
  actionLabel?: string;
  onPress: () => void;
};

export function PermissionRow({ icon, title, description, granted, optional, actionLabel, onPress }: Props) {
  return (
    <View style={styles.row}>
      <Text style={styles.icon}>{icon}</Text>
      <View style={styles.body}>
        <Text style={styles.title}>
          {title}
          {optional ? <Text style={styles.optional}>  optional</Text> : null}
        </Text>
        <Text style={styles.desc}>{description}</Text>
      </View>
      {granted ? (
        <Text style={styles.granted}>✓</Text>
      ) : (
        <Pressable style={styles.button} onPress={onPress}>
          <Text style={styles.buttonText}>{actionLabel ?? 'Allow'}</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  icon: { fontSize: 22, width: 34 },
  body: { flex: 1, paddingRight: 8 },
  title: { color: colors.text, fontSize: 15, fontWeight: '600' },
  optional: { color: colors.muted, fontSize: 11, fontWeight: '400' },
  desc: { color: colors.muted, fontSize: 12, marginTop: 2 },
  granted: { color: colors.ok, fontSize: 22, fontWeight: '700', width: 64, textAlign: 'center' },
  button: { backgroundColor: colors.primary, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 8 },
  buttonText: { color: '#fff', fontWeight: '700', fontSize: 13 },
});
