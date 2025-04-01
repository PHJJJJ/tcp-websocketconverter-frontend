// src/components/VideoPlayer.tsx
import React, { useEffect, useRef, useState, useCallback } from "react";
import { webSocketService } from "../services/WebSocketService";
import { ObjectInfo, LiveDataMetadata, CodecType } from "../types";

interface VideoPlayerProps {
  cameraId: number;
  width: number;
  height: number;
  showObjects?: boolean;
}

const VideoPlayer: React.FC<VideoPlayerProps> = ({
  cameraId,
  width,
  height,
  showObjects = true,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isConnected, setIsConnected] = useState(false);
  const [isReady, setIsReady] = useState(false); // TCP 연결 준비 상태
  const [error, setError] = useState<string | null>(null);
  const [currentObjects, setCurrentObjects] = useState<ObjectInfo[]>([]);
  const requestedRef = useRef(false); // 이미 요청이 수행되었는지 추적

  // Store decoder and context info
  const decoderRef = useRef<{
    decoder: any;
    ctx: CanvasRenderingContext2D | null;
    codecType: CodecType;
    extraData: Uint8Array | null;
  }>({
    decoder: null,
    ctx: null,
    codecType: CodecType.NONE,
    extraData: null,
  });

  // 비디오 요청 함수를 메모화하여 중복 호출 방지
  const requestVideo = useCallback(() => {
    if (requestedRef.current) {
      console.log(
        `Already requested video for camera ${cameraId}, skipping duplicate request`
      );
      return;
    }

    console.log(`Requesting video for camera ${cameraId}`);
    webSocketService.requestLiveVideo([cameraId]);
    requestedRef.current = true;
  }, [cameraId]);

  useEffect(() => {
    // 컴포넌트 마운트 시 요청 상태 초기화
    requestedRef.current = false;

    // 캔버스 컨텍스트 설정
    if (canvasRef.current) {
      decoderRef.current.ctx = canvasRef.current.getContext("2d");
    }

    // 웹소켓 메시지 핸들러 등록
    const unsubscribeMessage = webSocketService.onMessage((message) => {
      if (message.type === "connection") {
        setIsConnected(message.connected);

        // 연결 상태 변경 시 에러 메시지 초기화
        if (message.connected) {
          setError(null);
        } else {
          // 연결 끊김 시 요청 상태 초기화
          requestedRef.current = false;
          setIsReady(false);
        }
      } else if (message.type === "connectionReady" && message.connected) {
        // 연결 준비 완료 시 상태 업데이트
        console.log(`Connection ready received for camera ${cameraId}`);
        setIsReady(true);

        requestVideo();

        // 연결이 준비되었고 아직 요청하지 않았다면 비디오 요청
        // if (!requestedRef.current) {
        //   // 모든 컴포넌트가 동시에 요청하는 것을 방지하기 위해 약간의 지연 적용
        //   setTimeout(() => {
        //     console.log(
        //       `Requesting video for camera ${cameraId} after connection ready`
        //     );
        //     requestVideo();
        //   }, cameraId * 200); // 카메라 ID에 따라 요청 시간 차등화
        // }
      } else if (message.type === "error") {
        setError(message.message);
        // 에러 발생 시 요청 상태 초기화
        requestedRef.current = false;
      } else if (message.type === "liveData") {
        const liveData = message as LiveDataMetadata;

        // 요청한 카메라 ID에 대한 데이터만 처리
        if (liveData.cameraId === cameraId) {
          // 로그 최소화
          setCurrentObjects(liveData.objects);
        }
      }
    });

    // 바이너리 데이터 핸들러 등록
    const unsubscribeBinary = webSocketService.onBinary((data) => {
      // 로그 최소화
      processVideoData(data);
    });

    // 언마운트 시 정리
    return () => {
      unsubscribeMessage();
      unsubscribeBinary();

      // 디코더 정리
      if (decoderRef.current.decoder) {
        try {
          decoderRef.current.decoder.close();
        } catch (e) {
          console.error("Error closing decoder:", e);
        }
      }
    };
  }, [cameraId, requestVideo]);

  // Process binary video data
  const processVideoData = async (data: ArrayBuffer) => {
    if (!decoderRef.current.ctx) {
      return;
    }

    try {
      const dataView = new DataView(data);
      const codecValue = dataView.getInt32(0, false);
      const extraDataSize = dataView.getInt32(4, false);

      let frameData: Uint8Array;

      if (extraDataSize > 0) {
        // Store extra data for codec initialization
        decoderRef.current.extraData = new Uint8Array(
          data.slice(8, 8 + extraDataSize)
        );
        frameData = new Uint8Array(data.slice(8 + extraDataSize));
      } else {
        frameData = new Uint8Array(data.slice(8));
      }

      // If codec changed or decoder not initialized, create new decoder
      if (
        codecValue !== decoderRef.current.codecType ||
        !decoderRef.current.decoder
      ) {
        if (decoderRef.current.decoder) {
          try {
            await decoderRef.current.decoder.close();
          } catch (e) {
            console.error("Error closing decoder:", e);
          }
        }

        decoderRef.current.codecType = codecValue;

        switch (codecValue) {
          case CodecType.H264:
            await initializeH264Decoder(frameData);
            break;
          case CodecType.MJPEG:
            await renderMjpegFrame(frameData);
            break;
          default:
            console.error("Unsupported codec:", codecValue);
        }
      } else {
        // Decoder already initialized, just process the frame
        switch (decoderRef.current.codecType) {
          case CodecType.H264:
            await decodeH264Frame(frameData);
            break;
          case CodecType.MJPEG:
            await renderMjpegFrame(frameData);
            break;
        }
      }
    } catch (e) {
      console.error("Error processing video data:", e);
    }
  };

  // Initialize H264 decoder
  const initializeH264Decoder = async (frameData: Uint8Array) => {
    try {
      // Using browser's VideoDecoder API if available
      if ("VideoDecoder" in window) {
        const videoDecoder = new (window as any).VideoDecoder({
          output: (frame: any) => {
            renderVideoFrame(frame);
          },
          error: (error: any) => {
            console.error("Decoder error:", error);
          },
        });

        // Configure the decoder
        const config = {
          codec: "avc1.42E01F", // Baseline profile
          optimizeForLatency: true,
        };

        videoDecoder.configure(config);
        decoderRef.current.decoder = videoDecoder;

        // Decode the first frame
        await decodeH264Frame(frameData);
      } else {
        // Fallback - you can use a JavaScript-based decoder like Broadway.js
        console.warn("VideoDecoder API not available, using fallback");
        // Implementation would depend on the library you choose
      }
    } catch (e) {
      console.error("Error initializing H264 decoder:", e);
    }
  };

  // Decode H264 frame
  const decodeH264Frame = async (frameData: Uint8Array) => {
    if (!decoderRef.current.decoder) {
      console.error("Decoder not initialized");
      return;
    }

    try {
      const chunk = new (window as any).EncodedVideoChunk({
        type: "key", // or 'delta' based on frame type
        timestamp: performance.now(),
        data: frameData,
      });

      decoderRef.current.decoder.decode(chunk);
    } catch (e) {
      console.error("Error decoding H264 frame:", e);
    }
  };

  // Render MJPEG frame
  const renderMjpegFrame = async (frameData: Uint8Array) => {
    if (!decoderRef.current.ctx) {
      console.error("Canvas context not available");
      return;
    }

    try {
      // Create Blob from JPEG data
      const blob = new Blob([frameData], { type: "image/jpeg" });

      // Create object URL
      const url = URL.createObjectURL(blob);

      // Load image
      const img = new Image();

      img.onload = () => {
        // Draw image to canvas
        if (decoderRef.current.ctx) {
          decoderRef.current.ctx.drawImage(img, 0, 0, width, height);

          // Draw object bounding boxes if enabled
          if (showObjects) {
            drawObjectBoxes();
          }

          // Revoke object URL to free memory
          URL.revokeObjectURL(url);
        }
      };

      img.onerror = (err) => {
        console.error("Error loading JPEG image:", err);
        URL.revokeObjectURL(url);
      };

      img.src = url;
    } catch (e) {
      console.error("Error rendering MJPEG frame:", e);
    }
  };

  // Render video frame from VideoDecoder
  const renderVideoFrame = (frame: any) => {
    if (!decoderRef.current.ctx || !canvasRef.current) {
      console.error("Canvas context not available");
      frame.close();
      return;
    }

    try {
      // Draw the frame to canvas
      decoderRef.current.ctx.drawImage(frame, 0, 0, width, height);

      // Draw object bounding boxes if enabled
      if (showObjects) {
        drawObjectBoxes();
      }

      // Close the frame to free resources
      frame.close();
    } catch (e) {
      console.error("Error rendering video frame:", e);
      frame.close();
    }
  };

  // Draw object bounding boxes
  const drawObjectBoxes = () => {
    if (!decoderRef.current.ctx) {
      return;
    }

    const ctx = decoderRef.current.ctx;
    const canvas = canvasRef.current;

    if (!canvas) {
      return;
    }

    // Scale factors for object coordinates
    const scaleX = canvas.width / 100; // Assuming coordinates are in percentage
    const scaleY = canvas.height / 100;

    currentObjects.forEach((obj) => {
      // Calculate box coordinates
      const x = obj.x * scaleX;
      const y = obj.y * scaleY;
      const w = obj.width * scaleX;
      const h = obj.height * scaleY;

      // Draw bounding box
      ctx.strokeStyle = getObjectColor(obj.type);
      ctx.lineWidth = 2;
      ctx.strokeRect(x, y, w, h);

      // Draw label
      ctx.fillStyle = getObjectColor(obj.type);
      ctx.font = "12px Arial";
      ctx.fillText(
        `${obj.type} (${Math.round(obj.detectionScore * 100)}%)`,
        x,
        y - 5
      );
    });
  };

  // Get color for object type
  const getObjectColor = (type: string): string => {
    switch (type) {
      case "PERSON":
        return "red";
      case "CAR":
      case "SUV":
      case "VAN":
        return "blue";
      case "FACE_FULL":
      case "FACE_SIDE":
        return "yellow";
      default:
        return "green";
    }
  };

  return (
    <div className="video-player">
      {error && <div className="error-message">Error: {error}</div>}

      {!isConnected && !error && (
        <div className="connecting-message">Connecting to camera...</div>
      )}

      {isConnected && !isReady && !error && (
        <div className="waiting-message">Waiting for server connection...</div>
      )}

      <canvas
        ref={canvasRef}
        width={width}
        height={height}
        style={{
          border: "1px solid #ddd",
          background: "#000",
        }}
      />
    </div>
  );
};

export default VideoPlayer;
