# 프로젝트명

Java 백엔드와 React 프론트엔드로 구성된 웹 애플리케이션입니다.

## 프로젝트 구조

```
/
├── backend/          # Java 백엔드
│   ├── src/          # 소스 코드
│   ├── pom.xml       # Maven 설정 (또는 build.gradle)
│   └── ...
├── frontend/         # React 프론트엔드
│   ├── src/          # 소스 코드
│   ├── public/       # 정적 파일
│   ├── package.json  # NPM 설정
│   └── ...
├── .gitignore        # Git 제외 파일 설정
└── README.md         # 현재 파일
```

## 설치 및 실행 방법

### 사전 요구사항

- Java JDK 17 이상
- Maven 또는 Gradle
- Node.js 20 이상
- npm 또는 yarn

### 백엔드 설치 및 실행

```bash
# 백엔드 디렉토리로 이동
cd backend

# Maven을 사용하는 경우
mvn clean install
mvn spring-boot:run

# Gradle을 사용하는 경우
./gradlew build
./gradlew bootRun
```

### 프론트엔드 설치 및 실행

```bash
# 프론트엔드 디렉토리로 이동
cd frontend

# 의존성 설치
npm install
# 또는
yarn

# 개발 서버 시작
npm start
# 또는
yarn start
```

## Git 저장소 관리 (.gitignore)

이 프로젝트는 `.gitignore` 파일을 통해 Git 저장소에 불필요한 파일을 포함하지 않도록 설정되어 있습니다. 주요 제외 항목은 다음과 같습니다:

### 제외되는 파일들

- **컴파일된 바이너리**: `.class`, `.jar`, `.war` 등
- **빌드 결과물**: `target/`, `build/`, `dist/` 등
- **의존성 디렉토리**: `node_modules/`, Maven/Gradle 캐시
- **IDE 설정**: `.idea/`, `.vscode/`, `.classpath` 등
- **로컬 환경 설정**: `.env`, `.env.local` 등
- **로그 파일**: `*.log`
- **OS 생성 파일**: `.DS_Store`, `Thumbs.db` 등

### 중요 안내

이러한 파일들은 Git에 포함되지 않지만, 프로젝트 빌드 및 실행에는 문제가 없습니다. 위의 설치 및 실행 방법을 따르면 필요한 파일들이 자동으로 생성되거나 다운로드됩니다.

Git 저장소에서 제외되는 이유:

1. **자동 생성 파일**: 소스 코드로부터 빌드 시 자동으로 생성됨
2. **의존성 모듈**: 매우 용량이 크며, 의존성 관리 파일(pom.xml, package.json)을 통해 복원 가능
3. **개인 설정 파일**: 개발자마다 다른 설정을 유지할 수 있음
4. **환경별 설정**: 개발/테스트/운영 환경마다 다른 설정을 적용 가능

## 환경 설정

백엔드와 프론트엔드의 환경 설정은 각각 다음 파일을 참고하세요:

- 백엔드: `application.properties.example` 또는 `application.yml.example`
- 프론트엔드: `.env.example`

위 예제 파일을 복사하여 각각 `application.properties`(또는 `application.yml`)와 `.env` 파일을 생성하고 필요에 맞게 수정하세요.

## 기여 방법

1. 이 저장소를 포크(Fork)합니다.
2. 새 기능 브랜치를 생성합니다: `git checkout -b feature/amazing-feature`
3. 변경사항을 커밋합니다: `git commit -m 'Add some amazing feature'`
4. 브랜치에 푸시합니다: `git push origin feature/amazing-feature`
5. Pull Request를 생성합니다.

## 라이센스

이 프로젝트는 [라이센스명] 라이센스 하에 배포됩니다. 자세한 내용은 `LICENSE` 파일을 참조하세요.
