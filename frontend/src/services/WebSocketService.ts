// src/services/WebSocketService.ts
import { ServerConfig, WebSocketMessage, LiveDataMetadata } from "../types";

type MessageHandler = (message: WebSocketMessage) => void;
type BinaryHandler = (data: ArrayBuffer) => void;

export class WebSocketService {
  private videoWs: WebSocket | null = null;
  private controlWs: WebSocket | null = null;
  private messageHandlers: MessageHandler[] = [];
  private binaryHandlers: BinaryHandler[] = [];
  private config: ServerConfig | null = null;
  // 비디오와 컨트롤에 대한 별도의 재연결 카운터 사용
  private videoReconnectAttempts = 0;
  private controlReconnectAttempts = 0;
  private readonly MAX_RECONNECT_ATTEMPTS = 5;
  private currentCameraIds: number[] | null = null;
  private videoConnectionReady = false;
  private controlConnectionReady = false;
  private connectionMonitor: any | null = null;
  private isReconnecting = false; // 재연결 진행 중 여부를 추적
  private reconnectTimeout: any | null = null; // 재연결 타임아웃 추적

  // Connect to WebSocket server with debounce
  public connectVideo(config: ServerConfig): void {
    // 이미 연결 중이거나 재연결 중이면 중복 연결 방지
    if (this.videoWs && this.videoWs.readyState === WebSocket.CONNECTING) {
      console.log(
        "Video WebSocket already connecting, ignoring duplicate request"
      );
      return;
    }

    if (this.isReconnecting) {
      console.log(
        "Already attempting to reconnect, ignoring duplicate request"
      );
      return;
    }

    this.config = config;
    this.videoReconnectAttempts = 0; // 비디오 연결의 재연결 카운터만 초기화
    this.videoConnectionReady = false;
    this.isReconnecting = true; // 재연결 중 상태 설정

    // 기존 연결이 있으면 닫기
    if (this.videoWs) {
      console.log("Closing existing video WebSocket before creating new one");
      try {
        this.videoWs.onclose = null; // 기존 닫힘 핸들러 제거하여 중복 재연결 방지
        this.videoWs.close();
      } catch (e) {
        console.error("Error closing existing video WebSocket:", e);
      }
    }

    const wsUrl = `ws://${window.location.hostname}:8080/ws/video`;
    console.log(`Connecting to video WebSocket: ${wsUrl}`);
    this.videoWs = new WebSocket(wsUrl);

    this.videoWs.onopen = () => {
      console.log("Video WebSocket connected");

      // Send connect request
      if (this.videoWs && this.videoWs.readyState === WebSocket.OPEN) {
        console.log(
          `Sending video connect request to ${config.serverIp}:${config.serverPort}`
        );
        this.videoWs.send(
          JSON.stringify({
            type: "connect",
            serverIp: config.serverIp,
            serverPort: config.serverPort,
          })
        );
      }

      this.isReconnecting = false; // 재연결 완료
      this.notifyHandlers({
        type: "connection",
        connected: true,
        endpoint: "video",
      });
    };

    this.videoWs.onclose = (event) => {
      console.log(
        `Video WebSocket disconnected with code: ${event.code}, reason: ${event.reason}`
      );
      this.videoConnectionReady = false;
      this.notifyHandlers({
        type: "connection",
        connected: false,
        endpoint: "video",
      });

      // 코드 1000(정상 종료)이 아닌 경우에만 재연결 시도
      if (event.code !== 1000 && !this.isReconnecting) {
        this.attemptReconnect("video");
      }
    };

    this.videoWs.onerror = (error) => {
      console.error("Video WebSocket error:", error);
      this.notifyHandlers({
        type: "error",
        message: "WebSocket error",
        endpoint: "video",
      });
    };

    this.videoWs.onmessage = (event) => {
      if (typeof event.data === "string") {
        try {
          const message = JSON.parse(event.data) as WebSocketMessage;

          // 연결 상태 처리
          if (message.type === "connection" && message.connected) {
            console.log("TCP connection established for video");
          } else if (message.type === "connectionReady" && message.connected) {
            console.log(
              `TCP connection ready for video with clientKey: ${message.clientKey} - now safe to request video`
            );
            this.videoConnectionReady = true;

            // 저장된 카메라 ID가 있으면 자동으로 다시 요청
            if (this.currentCameraIds && this.currentCameraIds.length > 0) {
              console.log(
                `Auto-requesting video for cameras: ${this.currentCameraIds.join(
                  ", "
                )}`
              );
              // 중복 요청 방지를 위해 타임아웃 추가
              setTimeout(() => {
                this.requestLiveVideo(this.currentCameraIds!);
              }, 500);
            }
          } else if (message.type === "liveData") {
            // 로그 줄임 - 대량의 로그는 성능 문제 야기 가능
            // if (Math.random() < 0.05) {
            //   // 5% 확률로만 로그 출력
            //   console.log(
            //     `📊 Received liveData for camera ${message.cameraId}`
            //   );
            // }
          }

          this.notifyHandlers(message);
        } catch (e) {
          console.error("Error parsing WebSocket message:", e);
        }
      } else if (event.data instanceof Blob) {
        // 자바에서 Byte로 보내면 자바스크립트에는 Blob으로 들어온다. 이제 Blob 케이스를 직접 처리한다
        //console.log(`📹 Received Blob: ${event.data.size} bytes`);

        // Blob을 ArrayBuffer로 변환
        const reader = new FileReader();
        reader.onload = (e) => {
          if (e.target && e.target.result) {
            const arrayBuffer = e.target.result as ArrayBuffer;
            // console.log(
            //   `🔄 Converted Blob to ArrayBuffer: ${arrayBuffer.byteLength} bytes`
            // );
            this.notifyBinaryHandlers(arrayBuffer);
          }
        };
        reader.onerror = (err) => {
          console.error("❌ Error converting Blob to ArrayBuffer:", err);
        };
        reader.readAsArrayBuffer(event.data);
      } else {
        console.warn(`❓ Unhandled data type: ${typeof event.data}`);
        if (event.data && typeof event.data === "object") {
          console.log(
            `❓ Constructor: ${event.data.constructor?.name || "unknown"}`
          );
        }
      }
    };

    // 연결 모니터링 시작 - 더 긴 간격으로 변경
    this.monitorConnection();
  }

  public connectControl(config: ServerConfig): void {
    // 이미 연결 중이거나 재연결 중이면 중복 연결 방지
    if (this.controlWs && this.controlWs.readyState === WebSocket.CONNECTING) {
      console.log(
        "Control WebSocket already connecting, ignoring duplicate request"
      );
      return;
    }

    this.config = config;
    this.controlReconnectAttempts = 0; // 컨트롤 연결의 재연결 카운터만 초기화
    this.controlConnectionReady = false;

    // 기존 연결이 있으면 닫기
    if (this.controlWs) {
      console.log("Closing existing control WebSocket before creating new one");
      try {
        this.controlWs.onclose = null; // 기존 닫힘 핸들러 제거
        this.controlWs.close();
      } catch (e) {
        console.error("Error closing existing control WebSocket:", e);
      }
    }

    const wsUrl = `ws://${window.location.hostname}:8080/ws/control`;
    console.log(`Connecting to control WebSocket: ${wsUrl}`);
    this.controlWs = new WebSocket(wsUrl);

    this.controlWs.onopen = () => {
      console.log("Control WebSocket connected");

      // Send connect request
      if (this.controlWs && this.controlWs.readyState === WebSocket.OPEN) {
        console.log(
          `Sending control connect request to ${config.serverIp}:${config.serverPort}`
        );
        this.controlWs.send(
          JSON.stringify({
            type: "connect",
            serverIp: config.serverIp,
            serverPort: config.serverPort,
          })
        );
      }

      this.notifyHandlers({
        type: "connection",
        connected: true,
        endpoint: "control",
      });
    };

    this.controlWs.onclose = (event) => {
      console.log(
        `Control WebSocket disconnected with code: ${event.code}, reason: ${event.reason}`
      );
      this.controlConnectionReady = false;
      this.notifyHandlers({
        type: "connection",
        connected: false,
        endpoint: "control",
      });

      // 코드 1000(정상 종료)이 아닌 경우에만 재연결 시도
      if (event.code !== 1000) {
        this.attemptReconnect("control");
      }
    };

    this.controlWs.onerror = (error) => {
      console.error("Control WebSocket error:", error);
      this.notifyHandlers({
        type: "error",
        message: "WebSocket error",
        endpoint: "control",
      });
    };

    this.controlWs.onmessage = (event) => {
      if (typeof event.data === "string") {
        try {
          const message = JSON.parse(event.data) as WebSocketMessage;

          // 연결 상태 처리
          if (message.type === "connection" && message.connected) {
            console.log("TCP connection established for control");
          } else if (message.type === "connectionReady" && message.connected) {
            console.log(
              `TCP connection ready for control with clientKey: ${message.clientKey}`
            );
            this.controlConnectionReady = true;
          }

          this.notifyHandlers(message);
        } catch (e) {
          console.error("Error parsing WebSocket message:", e);
        }
      }
    };
  }

  // Disconnect WebSocket with proper cleanup
  public disconnect(): void {
    console.log("Disconnecting WebSockets");
    this.videoConnectionReady = false;
    this.controlConnectionReady = false;
    this.isReconnecting = false;

    // 진행 중인 재연결 타임아웃 취소
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }

    if (this.connectionMonitor) {
      clearInterval(this.connectionMonitor);
      this.connectionMonitor = null;
    }

    if (this.videoWs) {
      // onclose 핸들러 제거하여 불필요한 재연결 방지
      this.videoWs.onclose = null;
      this.videoWs.close();
      this.videoWs = null;
    }

    if (this.controlWs) {
      // onclose 핸들러 제거하여 불필요한 재연결 방지
      this.controlWs.onclose = null;
      this.controlWs.close();
      this.controlWs = null;
    }
  }

  // Request live video with connection readiness check
  public requestLiveVideo(cameraIds: number[]): void {
    // 카메라 ID 저장
    this.currentCameraIds = [...cameraIds];

    if (!this.videoWs || this.videoWs.readyState !== WebSocket.OPEN) {
      console.error("Video WebSocket not connected");

      // 요청을 저장해두고 나중에 연결되면 자동으로 요청
      if (
        this.config &&
        this.videoReconnectAttempts < this.MAX_RECONNECT_ATTEMPTS
      ) {
        console.log("Will auto-request when connection is established");
        if (!this.isReconnecting) {
          this.connectVideo(this.config);
        }
      }
      return;
    }

    // TCP 연결이 준비되지 않았으면 요청을 보내지 않고 대기
    if (!this.videoConnectionReady) {
      console.log(
        "TCP connection not ready, request will be sent automatically when ready"
      );
      return;
    }

    console.log(`Requesting live video for cameras: ${cameraIds.join(", ")}`);
    this.videoWs.send(
      JSON.stringify({
        type: "liveInfo",
        cameraIds,
        serverIp: this.config?.serverIp,
        serverPort: this.config?.serverPort,
      })
    );
  }

  // Request playback
  public requestPlayback(
    cameraIds: number[],
    startTime: Date,
    duration: number
  ): void {
    if (!this.controlWs || this.controlWs.readyState !== WebSocket.OPEN) {
      console.error("Control WebSocket not connected");

      // 요청할 수 없는 상황이면 연결부터 시도
      if (
        this.config &&
        this.controlReconnectAttempts < this.MAX_RECONNECT_ATTEMPTS
      ) {
        console.log("Will connect control WebSocket first");
        this.connectControl(this.config);
      }
      return;
    }

    if (!this.controlConnectionReady) {
      console.warn("TCP control connection not ready, request will be queued");
      // 요청 저장 로직 추가 가능
      return;
    }

    console.log(
      `Requesting playback for cameras: ${cameraIds.join(
        ", "
      )}, startTime: ${startTime.toISOString()}, duration: ${duration}`
    );
    this.controlWs.send(
      JSON.stringify({
        type: "playbackInfo",
        cameraIds,
        startTime: startTime.toISOString(),
        duration,
        serverIp: this.config?.serverIp,
        serverPort: this.config?.serverPort,
      })
    );
  }

  // Send playback control command
  public sendPlaybackControl(controlType: number, speed?: number): void {
    if (!this.controlWs || this.controlWs.readyState !== WebSocket.OPEN) {
      console.error("Control WebSocket not connected");
      return;
    }

    if (!this.controlConnectionReady) {
      console.warn("Control connection not ready, command might fail");
    }

    const command: any = {
      type: "playbackControl",
      controlType,
      serverIp: this.config?.serverIp,
      serverPort: this.config?.serverPort,
    };

    if (speed !== undefined) {
      command.speed = speed;
    }

    console.log(
      `Sending playback control: type=${controlType}, speed=${speed}`
    );
    this.controlWs.send(JSON.stringify(command));
  }

  // Register message handler
  public onMessage(handler: MessageHandler): () => void {
    this.messageHandlers.push(handler);
    return () => {
      this.messageHandlers = this.messageHandlers.filter((h) => h !== handler);
    };
  }

  // Register binary handler
  public onBinary(handler: BinaryHandler): () => void {
    this.binaryHandlers.push(handler);
    return () => {
      this.binaryHandlers = this.binaryHandlers.filter((h) => h !== handler);
    };
  }

  // Get connection state
  public isVideoReady(): boolean {
    return (
      this.videoConnectionReady &&
      this.videoWs !== null &&
      this.videoWs.readyState === WebSocket.OPEN
    );
  }

  public isControlReady(): boolean {
    return (
      this.controlConnectionReady &&
      this.controlWs !== null &&
      this.controlWs.readyState === WebSocket.OPEN
    );
  }

  // Monitor connection state - less frequent
  private monitorConnection() {
    // 기존 모니터 중지
    if (this.connectionMonitor) {
      clearInterval(this.connectionMonitor);
      this.connectionMonitor = null;
    }

    this.connectionMonitor = setInterval(() => {
      // 비디오 연결 상태 확인 - 연결 중이거나 이미 재연결 중인 경우 제외
      if (
        this.videoWs &&
        this.videoWs.readyState !== WebSocket.OPEN &&
        this.videoWs.readyState !== WebSocket.CONNECTING &&
        !this.isReconnecting &&
        this.config
      ) {
        console.log("Video connection lost, attempting to reconnect...");
        this.connectVideo(this.config);
      }
    }, 15000); // 15초마다 확인으로 변경 (기존 5초에서 늘림)
  }

  // Stop connection monitoring
  public stopMonitoring() {
    if (this.connectionMonitor) {
      clearInterval(this.connectionMonitor);
      this.connectionMonitor = null;
    }
  }

  // Notify all message handlers
  private notifyHandlers(message: WebSocketMessage): void {
    for (const handler of this.messageHandlers) {
      try {
        handler(message);
      } catch (e) {
        console.error("Error in message handler:", e);
      }
    }
  }

  // Notify all binary handlers
  private notifyBinaryHandlers(data: ArrayBuffer): void {
    for (const handler of this.binaryHandlers) {
      try {
        handler(data);
      } catch (e) {
        console.error("Error in binary handler:", e);
      }
    }
  }

  // Attempt to reconnect with better backoff
  private attemptReconnect(endpoint: "video" | "control"): void {
    const attempts =
      endpoint === "video"
        ? this.videoReconnectAttempts
        : this.controlReconnectAttempts;
    const maxAttempts = this.MAX_RECONNECT_ATTEMPTS;

    if (attempts >= maxAttempts || !this.config) {
      console.log(
        `Max reconnect attempts (${maxAttempts}) reached for ${endpoint}`
      );
      return;
    }

    // 재연결 카운터 증가
    if (endpoint === "video") {
      this.videoReconnectAttempts++;
    } else {
      this.controlReconnectAttempts++;
    }

    // 기하급수적 백오프 (최대 30초)
    const delay = Math.min(Math.pow(2, attempts) * 1000, 30000);

    console.log(
      `Attempting to reconnect ${endpoint} in ${delay}ms (attempt ${
        attempts + 1
      }/${maxAttempts})`
    );

    // 진행 중인 재연결 타임아웃 취소
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }

    this.reconnectTimeout = setTimeout(() => {
      this.reconnectTimeout = null;

      // 재연결 시도 전 상태 다시 확인
      if (endpoint === "video") {
        if (this.videoWs && this.videoWs.readyState === WebSocket.OPEN) {
          console.log("Video already connected, skipping reconnect");
          this.isReconnecting = false;
          return;
        }
        this.connectVideo(this.config!);
      } else {
        if (this.controlWs && this.controlWs.readyState === WebSocket.OPEN) {
          console.log("Control already connected, skipping reconnect");
          return;
        }
        this.connectControl(this.config!);
      }
    }, delay);
  }
}

// Singleton instance
export const webSocketService = new WebSocketService();
