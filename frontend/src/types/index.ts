export interface ServerConfig {
  serverIp: string;
  serverPort: number;
}

export enum CodecType {
  NONE = 0,
  RAW = 1,
  MJPEG = 2,
  MPEG4 = 3,
  H264 = 4,
}

export enum IntraCodeType {
  UNKNOWN = 0,
  INTRA = 1,
  PREDICT = 2,
  BIPREDICT = 3,
}

export enum ObjectType {
  CAR = 0,
  SUV = 1,
  VAN = 2,
  PERSON = 31,
  FACE_FULL = 32,
  FACE_SIDE = 33,
}

export interface ObjectInfo {
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
  detectionScore: number;
}

export interface LiveDataMetadata {
  type: "liveData";
  cameraId: number;
  timestamp: number;
  codec: string;
  intraCode: string;
  objects: ObjectInfo[];
}

export interface WebSocketMessage {
  type: string;
  [key: string]: any;
}

export interface ConnectionMessage extends WebSocketMessage {
  type: "connection";
  connected: boolean;
}

export interface ErrorMessage extends WebSocketMessage {
  type: "error";
  message: string;
}

export enum PlaybackControlType {
  PLAY = 1,
  STOP = 2,
  SPEED = 3,
}

export enum PlaybackSpeedType {
  X1 = 1,
  X2 = 2,
  X4 = 3,
  X8 = 4,
  MAX = 5,
}
