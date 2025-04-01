// src/App.tsx
import React, { useEffect, useState } from "react";
import VideoPlayer from "./components/VideoPlayer";
import VideoControls from "./components/VideoControls";
import { webSocketService } from "./services/WebSocketService";
import { ServerConfig } from "./types";
import "./App.css";

const App: React.FC = () => {
  const [serverConfig, setServerConfig] = useState<ServerConfig>({
    serverIp: "172.20.0.161",
    serverPort: 6990,
  });
  const [mode, setMode] = useState<"live" | "playback">("live");
  const [cameraId, setCameraId] = useState<number>(1000361);
  const [availableCameras, setAvailableCameras] = useState<number[]>([
    1000361, 2, 3, 4,
  ]);
  const [isConfiguring, setIsConfiguring] = useState<boolean>(true);
  const [isConnected, setIsConnected] = useState<boolean>(false);

  useEffect(() => {
    // Register connection status handler
    const unsubscribe = webSocketService.onMessage((message) => {
      if (message.type === "connection") {
        setIsConnected(message.connected);
      }
    });

    // Clean up
    return () => {
      unsubscribe();
      //webSocketService.disconnect();
    };
  }, []);

  // Handle server config change
  const handleConfigChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;

    setServerConfig((prev) => ({
      ...prev,
      [name]: name === "serverPort" ? parseInt(value) : value,
    }));
  };

  // Handle server connection
  const handleConnect = () => {
    setIsConfiguring(false);

    // Connect to WebSocket endpoints
    webSocketService.connectVideo(serverConfig);
    webSocketService.connectControl(serverConfig);
  };

  // Handle camera selection
  const handleCameraChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setCameraId(parseInt(e.target.value));
  };

  // Handle mode switch
  const handleModeSwitch = () => {
    setMode((prev) => (prev === "live" ? "playback" : "live"));
  };

  return (
    <div className="app">
      <header className="app-header">
        <h1>RexWatch Video Client</h1>
        <div className="connection-status">
          Status: {isConnected ? "Connected" : "Disconnected"}
        </div>
      </header>

      {isConfiguring ? (
        <div className="config-panel">
          <h2>Server Configuration</h2>
          <div className="form-group">
            <label htmlFor="serverIp">Server IP:</label>
            <input
              type="text"
              id="serverIp"
              name="serverIp"
              value={serverConfig.serverIp}
              onChange={handleConfigChange}
            />
          </div>
          <div className="form-group">
            <label htmlFor="serverPort">Server Port:</label>
            <input
              type="number"
              id="serverPort"
              name="serverPort"
              value={serverConfig.serverPort}
              onChange={handleConfigChange}
            />
          </div>
          <button onClick={handleConnect} className="connect-button">
            Connect
          </button>
        </div>
      ) : (
        <div className="video-container">
          <div className="camera-selector">
            <label htmlFor="camera">Camera:</label>
            <select id="camera" value={cameraId} onChange={handleCameraChange}>
              {availableCameras.map((id) => (
                <option key={id} value={id}>
                  Camera {id}
                </option>
              ))}
            </select>
          </div>

          <VideoControls mode={mode} onSwitchMode={handleModeSwitch} />

          <VideoPlayer
            cameraId={cameraId}
            width={800}
            height={450}
            showObjects={true}
          />

          <button
            onClick={() => setIsConfiguring(true)}
            className="config-button"
          >
            Change Server Config
          </button>
        </div>
      )}
    </div>
  );
};

export default App;
