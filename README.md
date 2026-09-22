# 부서업무 길라잡이 멀티 에이전트

웹 취약점 조치 업무를 중심으로 여러 업무 에이전트를 한 화면에서 사용할 수 있도록 구성한 UI 프로토타입입니다.

Java 17과 Spring Boot 기반으로 실행되며, 별도의 프론트엔드 빌드 과정 없이 HTML, CSS, JavaScript 정적 화면을 제공합니다.

## 1. 프로젝트 소개

사용자가 업무 유형에 맞는 에이전트를 직접 선택하고, 필요한 정보를 입력해 분석 결과와 조치 가이드를 확인하는 흐름을 시연합니다.

현재 중심 기능은 `웹 취약점 에이전트`이며 다음 과정을 화면에서 확인할 수 있습니다.

```text
취약점 정보 입력
→ 유사 사례 및 수정 방향 분석
→ 영향도와 점검 항목 확인
→ 사용자 조치 완료 등록
→ 위키 저장 결과 확인
```

## 2. 현재 구현 범위

이 프로젝트는 실제 운영 시스템이 아닌 화면 검토용 프로토타입입니다.

- 웹 취약점 분석 결과는 Spring Boot Mock API가 반환합니다.
- 위키 검색 및 저장 결과는 예시 데이터입니다.
- 대시보드 통계는 고정된 데모 데이터입니다.
- 배치 에이전트와 장애예방 에이전트는 준비 화면만 제공합니다.
- 에이전트 설정값은 브라우저에 저장되며 실제 분석 API 호출에는 아직 사용되지 않습니다.

## 3. 주요 화면 및 기능

### 웹 취약점 에이전트

- 취약점명, 시스템명, URL, 증상 및 소스 코드 입력
- 예시 데이터를 자동으로 입력하는 `샘플 불러오기`
- 분석 진행 상태 표시
- 유사 위키 사례 제공
- 권장 수정 코드 및 복사 기능
- 예상 영향 범위와 점검 체크리스트 제공
- 조치 결과 등록 및 위키 저장 완료 화면

### 배치 및 장애예방 에이전트

- 좌측 메뉴에서 독립 화면으로 이동
- 상세 기능 연결 전 준비 상태 제공

### 에이전트 설정

- 웹 취약점, 배치, 장애예방 에이전트별 설정 관리
- API URL, API Key, 모델명, 타임아웃 입력
- API Key 보기 및 숨김
- 브라우저 `localStorage`를 이용한 설정 복원

### 대시보드

- 전체 분석 건수
- 에이전트별 이용 분포
- 조치 완료율
- 주요 개선 주제

## 4. 기술 스택

| 구분 | 기술 |
| --- | --- |
| Runtime | Java 17 |
| Backend | Spring Boot 3.3.5, Spring Web |
| Frontend | HTML, CSS, Vanilla JavaScript |
| Build | Maven Wrapper |
| Test | JUnit 5, Spring MockMvc |

## 5. 실행 환경

- JDK 17 이상
- Git 또는 프로젝트 ZIP 파일
- 최신 Chrome, Edge, Safari 등 웹 브라우저

Maven은 별도로 설치하지 않아도 됩니다. 프로젝트에 포함된 Maven Wrapper가 필요한 도구를 내려받아 실행합니다.

Java 버전은 다음 명령으로 확인할 수 있습니다.

```bash
java -version
```

## 6. 실행 방법

### macOS / Linux

프로젝트 루트에서 실행합니다.

```bash
./mvnw spring-boot:run
```

### Windows

```bat
mvnw.cmd spring-boot:run
```

서버가 시작되면 브라우저에서 다음 주소를 엽니다.

```text
http://localhost:8080
```

`8080` 포트를 이미 사용 중이면 다른 포트를 지정할 수 있습니다.

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

## 7. 시연 방법

1. 좌측 메뉴에서 `웹 취약점 에이전트`를 선택합니다.
2. `샘플 불러오기`를 눌러 예시 취약점 정보를 입력합니다.
3. `분석 시작`을 눌러 분석 결과 화면으로 이동합니다.
4. 유사 사례, 권장 코드, 영향도와 체크리스트를 확인합니다.
5. `조치 완료 등록`을 눌러 조치 결과를 입력합니다.
6. 위키 저장 완료 결과를 확인합니다.
7. 좌측 `대시보드`에서 에이전트 이용 현황을 확인합니다.
8. `에이전트 설정`에서 에이전트별 API 입력 화면을 확인합니다.

## 8. 프로젝트 구조

```text
.
├── pom.xml
├── mvnw
├── mvnw.cmd
├── README.md
└── src
    ├── main
    │   ├── java/com/bankinginfo/guide
    │   │   ├── DepartmentGuideApplication.java
    │   │   └── api
    │   │       ├── AgentController.java
    │   │       └── DashboardController.java
    │   └── resources
    │       ├── application.properties
    │       └── static
    │           ├── index.html
    │           ├── styles.css
    │           └── app.js
    └── test
        └── java/com/bankinginfo/guide
            └── DepartmentGuideApplicationTests.java
```

| 경로 | 역할 |
| --- | --- |
| `DepartmentGuideApplication.java` | Spring Boot 실행 진입점 |
| `AgentController.java` | 분석 및 자산화 Mock API |
| `DashboardController.java` | 대시보드 Mock API |
| `static/index.html` | 전체 화면 구조와 팝업 |
| `static/styles.css` | 데스크톱 및 모바일 화면 스타일 |
| `static/app.js` | 화면 전환, API 호출 및 설정 저장 |

## 9. 주요 API

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/api/web/analyze` | 웹 취약점 분석 결과 반환 |
| `POST` | `/api/batch/analyze` | 배치 에이전트 준비 상태 반환 |
| `POST` | `/api/assetize` | 조치 결과의 위키 저장 결과 반환 |
| `GET` | `/api/dashboard/summary` | 대시보드 요약 데이터 반환 |

### 웹 취약점 분석 요청 예시

```json
{
  "vulnId": "WEB-2026-021",
  "vulnTitle": "SQL Injection 입력값 검증 미흡",
  "systemName": "인터넷뱅킹 포털",
  "url": "https://service.example.com/api/accounts",
  "checkQuarter": "2026년 3분기",
  "symptom": "특수문자 입력 시 DB 오류 메시지 노출",
  "asIsCode": "SELECT * FROM account WHERE customer_id = ..."
}
```

`vulnTitle`은 필수이며 `vulnId`와 `asIsCode`를 포함한 나머지 항목은 선택 입력입니다.

## 10. 에이전트 설정 방식

좌측 `에이전트 설정`을 누르면 에이전트별 API 연결 정보를 입력할 수 있습니다.

설정값은 다음 키로 브라우저 `localStorage`에 저장됩니다.

```text
departmentGuideAgentConfigs
```

현재 설정 화면은 연결 정보를 입력하고 복원하는 단계까지만 구현되어 있습니다. 실제 외부 API 호출은 Spring Boot의 연동 서비스가 추가된 이후 연결할 수 있습니다.

## 11. 테스트 방법

프로젝트 루트에서 다음 명령을 실행합니다.

### macOS / Linux

```bash
./mvnw test
```

### Windows

```bat
mvnw.cmd test
```

현재 테스트는 웹 취약점 분석 API와 대시보드 요약 API의 응답을 검증합니다.
