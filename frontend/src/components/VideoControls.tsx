// src/components/VideoControls.tsx
import React, { useState, useEffect } from "react";
import { webSocketService } from "../services/WebSocketService";
import { PlaybackControlType, PlaybackSpeedType } from "../types";

interface VideoControlsProps {
  mode: "live" | "playback";
  onSwitchMode: () => void;
}

const VideoControls: React.FC<VideoControlsProps> = ({
  mode,
  onSwitchMode,
}) => {
  const [currentSpeed, setCurrentSpeed] = useState<PlaybackSpeedType>(
    PlaybackSpeedType.X1
  );
  const [isPlaying, setIsPlaying] = useState(false);
  const [seekTime, setSeekTime] = useState<string>("");
  const [isControlReady, setIsControlReady] = useState(false);
  const [lastCommand, setLastCommand] = useState<{
    type: "playPause" | "speed" | "seek";
    params?: any;
  } | null>(null);

  // 컨트롤 연결 상태 모니터링
  useEffect(() => {
    const unsubscribe = webSocketService.onMessage((message) => {
      if (
        message.type === "connectionReady" &&
        message.connected &&
        message.endpoint === "control"
      ) {
        setIsControlReady(true);

        // 대기 중인 명령이 있으면 실행
        if (lastCommand && mode === "playback") {
          executeCommand(lastCommand.type, lastCommand.params);
          setLastCommand(null);
        }
      } else if (
        message.type === "connection" &&
        !message.connected &&
        message.endpoint === "control"
      ) {
        setIsControlReady(false);
      }
    });

    return () => {
      unsubscribe();
    };
  }, [lastCommand, mode]);

  // 명령 실행 함수
  const executeCommand = (
    type: "playPause" | "speed" | "seek",
    params?: any
  ) => {
    if (!isControlReady) {
      console.log("Control connection not ready, saving command for later");
      setLastCommand({ type, params });
      return;
    }

    switch (type) {
      case "playPause":
        if (isPlaying) {
          webSocketService.sendPlaybackControl(PlaybackControlType.STOP);
          setIsPlaying(false);
        } else {
          webSocketService.sendPlaybackControl(PlaybackControlType.PLAY);
          setIsPlaying(true);
        }
        break;
      case "speed":
        webSocketService.sendPlaybackControl(PlaybackControlType.SPEED, params);
        setCurrentSpeed(params);
        break;
      case "seek":
        // 시크 명령 처리
        try {
          const timeDate = new Date(params);
          if (!isNaN(timeDate.getTime())) {
            // 구현 필요
            console.log("Seeking to:", timeDate);
          }
        } catch (error) {
          console.error("Invalid date format:", error);
        }
        break;
    }
  };

  // Handle play/pause
  const handlePlayPause = () => {
    if (mode === "playback") {
      executeCommand("playPause");
    }
  };

  // Handle speed change
  const handleSpeedChange = (speed: PlaybackSpeedType) => {
    if (mode === "playback") {
      executeCommand("speed", speed);
    }
  };

  // Handle seek time change
  const handleSeekTimeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSeekTime(e.target.value);
  };

  // Handle seek submit
  const handleSeekSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    if (mode === "playback" && seekTime) {
      executeCommand("seek", seekTime);
    }
  };

  // 모드 전환 시 재생 상태 리셋
  useEffect(() => {
    if (mode === "live") {
      setIsPlaying(false);
    }
  }, [mode]);

  return (
    <div className="video-controls">
      <div className="mode-switch">
        <button
          onClick={onSwitchMode}
          className={`mode-button ${mode === "live" ? "active" : ""}`}
        >
          {mode === "live" ? "Live Mode" : "Switch to Live"}
        </button>
      </div>

      {mode === "playback" && (
        <div className="playback-controls">
          {!isControlReady && (
            <div className="control-status">
              Connecting to playback server...
            </div>
          )}

          <button
            onClick={handlePlayPause}
            className="control-button"
            disabled={!isControlReady}
          >
            {isPlaying ? "Pause" : "Play"}
          </button>

          <div className="speed-controls">
            <span>Speed:</span>
            <button
              onClick={() => handleSpeedChange(PlaybackSpeedType.X1)}
              className={`speed-button ${
                currentSpeed === PlaybackSpeedType.X1 ? "active" : ""
              }`}
              disabled={!isControlReady}
            >
              1x
            </button>
            <button
              onClick={() => handleSpeedChange(PlaybackSpeedType.X2)}
              className={`speed-button ${
                currentSpeed === PlaybackSpeedType.X2 ? "active" : ""
              }`}
              disabled={!isControlReady}
            >
              2x
            </button>
            <button
              onClick={() => handleSpeedChange(PlaybackSpeedType.X4)}
              className={`speed-button ${
                currentSpeed === PlaybackSpeedType.X4 ? "active" : ""
              }`}
              disabled={!isControlReady}
            >
              4x
            </button>
            <button
              onClick={() => handleSpeedChange(PlaybackSpeedType.X8)}
              className={`speed-button ${
                currentSpeed === PlaybackSpeedType.X8 ? "active" : ""
              }`}
              disabled={!isControlReady}
            >
              8x
            </button>
          </div>

          <form onSubmit={handleSeekSubmit} className="seek-form">
            <input
              type="datetime-local"
              value={seekTime}
              onChange={handleSeekTimeChange}
              className="seek-input"
              disabled={!isControlReady}
            />
            <button
              type="submit"
              className="seek-button"
              disabled={!isControlReady || !seekTime}
            >
              Seek
            </button>
          </form>
        </div>
      )}
    </div>
  );
};

export default VideoControls;
