import React from 'react';
import { View, Text, TouchableOpacity, ScrollView } from 'react-native';
import { useAppStore } from '@/store/useAppStore';
import type { GatewayConfig } from '@/types/config';
import { GatewayCard } from '@/components/common/GatewayCard';
import { StatusIndicator } from '@/components/common/StatusIndicator';
import { wakeUpManager } from '@/services/wake/WakeUpManager';

export function HomeScreen({ navigation }: { navigation: { navigate: (name: string) => void; goBack: () => void } }) {
  const { config, activeGateway } = useAppStore();
  const gateways = config.gateways;

  const handleActivate = async () => {
    try {
      await wakeUpManager.activate();
      navigation.navigate('Session');
    } catch (error) {
      console.error('Activation failed:', error);
    }
  };

  return (
    <View style={{ flex: 1, backgroundColor: '#0a0a0f' }}>
      {/* Header */}
      <View style={{
        flexDirection: 'row' as const,
        justifyContent: 'space-between' as const,
        alignItems: 'center' as const,
        paddingHorizontal: 20,
        paddingTop: 60,
        paddingBottom: 16,
      }}>
        <Text style={{ color: '#fff', fontSize: 24, fontWeight: 'bold' as const }}>
          🦞 MobileClaw
        </Text>
        <TouchableOpacity onPress={() => navigation.navigate('Settings')}>
          <Text style={{ color: '#888', fontSize: 18 }}>⚙️</Text>
        </TouchableOpacity>
      </View>

      {/* Status bar */}
      <View style={{ paddingHorizontal: 20, marginBottom: 16 }}>
        <StatusIndicator
          status={'disconnected'}
          gatewayName={activeGateway?.name}
        />
      </View>

      {/* Gateway list */}
      <ScrollView
        style={{ flex: 1, paddingHorizontal: 20 }}
        contentContainerStyle={{ gap: 12, paddingBottom: 20 }}
      >
        {gateways.length === 0 ? (
          /* Empty state */
          <View style={{
            flex: 1,
            justifyContent: 'center' as const,
            alignItems: 'center' as const,
            paddingVertical: 60,
          }}>
            <Text style={{ fontSize: 48, marginBottom: 16 }}>🦞</Text>
            <Text style={{ color: '#888', fontSize: 16, textAlign: 'center' as const }}>
              暂无已配置的 Gateway{'\n'}
              点击右上角 ⚙️ 添加你的龙虾
            </Text>
          </View>
        ) : (
          gateways.map((gw: GatewayConfig) => (
            <GatewayCard
              key={gw.id}
              gateway={gw}
              isActive={activeGateway?.id === gw.id}
              isConnected={false}
              onPress={() => {
                useAppStore.getState().setActiveGateway(gw);
              }}
            />
          ))
        )}
      </ScrollView>

      {/* Big "Tap to Talk" button */}
      <View style={{
        paddingHorizontal: 20,
        paddingVertical: 20,
        paddingBottom: 40,
      }}>
        <TouchableOpacity
          onPress={handleActivate}
          activeOpacity={0.7}
          style={{
            backgroundColor: '#22c55e',
            borderRadius: 16,
            paddingVertical: 18,
            alignItems: 'center' as const,
          }}
        >
          <Text style={{ color: '#000', fontSize: 18, fontWeight: '700' as const }}>
            🎤 Tap to Talk
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}
