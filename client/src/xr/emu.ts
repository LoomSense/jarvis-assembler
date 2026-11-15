// Development only: Meta's Immersive Web Emulation Runtime (IWER 2.8.0) as a Quest 3, with a synthetic room
// that has a table, so the headset experience can be built and tested on a laptop.
//
// This file is reached only through `if (import.meta.env.DEV && ...) await import('./emu')` in xrMain.ts.
// In a production build that condition is the constant `false`, so neither this file nor the iwer packages
// (dev dependencies) are bundled.
//
// The calls are the ones of iwer 2.8.0's source; the published READMEs are older:
// installRuntime() does nothing when navigator.xr already exists unless forceInstall is set.

/** What the tests use of the emulated device. The full type is iwer's XRDevice. */
export interface EmulatedDevice {
  position: { set(x: number, y: number, z: number): void; x: number; y: number; z: number }
  quaternion: { set(x: number, y: number, z: number, w: number): void }
  primaryInputMode: 'controller' | 'hand'
  remote: { dispatch(method: string, params?: Record<string, unknown>): Promise<unknown> }
  activeSession?: XRSession
}

/**
 * Install the emulated headset before anything asks navigator.xr a question.
 * @param devui show IWER's on-screen controls (move the head and hands with the mouse and keyboard)
 * @param room one of @iwer/sem's built-in rooms: home_office, kitchen, conference_room, ...
 * @param deny session features the emulated headset refuses (to rehearse a declined prompt): hand-tracking, hit-test, anchors, plane-detection
 */
export async function installEmulator(devui: boolean, room: string, deny: string[] = []): Promise<EmulatedDevice> {
  const { XRDevice, metaQuest3 } = await import('iwer')
  const config = deny.length ? { ...metaQuest3, supportedFeatures: metaQuest3.supportedFeatures.filter((f) => !deny.includes(f)) } : metaQuest3
  const device = new XRDevice(config)
  device.installRuntime({ forceInstall: true })
  if (devui) device.installDevUI((await import('@iwer/devui')).DevUI)
  device.installSEM((await import('@iwer/sem')).SyntheticEnvironmentModule)
  await device.sem!.loadDefaultEnvironment(room)
  device.primaryInputMode = 'hand'
  return device as unknown as EmulatedDevice
}
