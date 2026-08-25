# BẢN THUYẾT MINH GIẢI PHÁP KIẾN TRÚC & SƠ ĐỒ LUỒNG ASCII: HỆ THỐNG BANKING AI AGENT KHÉP KÍN CÓ GIÁM SÁT CHI PHÍ & TỰ PHỤC HỒI (SELF-HEALING)

---

## 1. TỔNG QUAN HỆ THỐNG & BỐI CẢNH NGHIỆP VỤ

Trong hệ thống ngân hàng số **RikkeiPay Assistant**, phân hệ **Banking AI Agent** chịu trách nhiệm tiếp nhận các yêu cầu ngôn ngữ tự nhiên từ khách hàng (ví dụ: *"Chuyển 500000 VND cho Nguyen Van B tai Vietcombank noi dung tra tien an"*), bóc tách thành dữ liệu giao dịch có cấu trúc (`TransactionDetails`), sau đó chuyển tiếp sang hệ thống Core Banking để thực hiện giao dịch chuyển khoản.

Để đáp ứng tiêu chuẩn khắt khe của ngành tài chính ngân hàng:
1. **Tính sẵn sàng cao (High Availability & Resilience):** Không được phép từ chối giao dịch của khách hàng khi nhà cung cấp AI bên thứ ba (Cloud LLM - OpenRouter/Gemini) gặp sự cố (Rate Limit HTTP 429, Server Error 503, gián đoạn cáp quang quốc tế). Hệ thống phải có cơ chế **Tự phục hồi (Self-Healing / Failover)** tức thời sang mô hình AI nội bộ cục bộ (**Local Ollama** như `qwen2.5` hoặc `llama3`).
2. **Minh bạch tài chính & Kiểm toán chi phí (LLMOps Cost Observability):** Toàn bộ token đầu vào (Input) và đầu ra (Output) phải được ghi nhận và tính toán chính xác bằng `BigDecimal` (tránh lỗi số thực IEEE 754), đính kèm Metadata (`department`, `userId`, `environment`, `modelUsed`) và đẩy về Langfuse Dashboard để phục vụ đối soát chi phí theo phòng ban.
3. **Giám sát phân tán & Truy vết tập trung (End-to-End Tracing):** Tích hợp OpenTelemetry `trace_id` đồng nhất từ Filter MDC cho tới Langfuse Trace Tree.

---

## 2. SƠ ĐỒ LUỒNG ASCII TOÀN BỘ VÒNG ĐỜI CỦA MỘT REQUEST (END-TO-END FLOW)

Dưới đây là sơ đồ luồng ASCII chi tiết thể hiện toàn bộ chu trình xử lý của một yêu cầu chuyển khoản từ lúc Client gửi đến khi nhận phản hồi:

```
[ CLIENT / MOBILE APP ]
        │
        │ HTTP POST /api/v1/banking/process
        │ Payload: { "message": "...", "userId": "USR_999", "department": "FINANCE" }
        ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. TẦNG BẢO VỆ NGỮ CẢNH & ĐỊNH DANH (TraceMdcFilter)                                   │
│    ├─ Trích xuất OpenTelemetry SpanContext: TraceId = "4bf92f3577b34da6a3ce929d0e0e4736"│
│    ├─ Nạp vào SLF4J MDC: MDC.put("trace_id", traceId)                                 │
│    └─ Thiết lập khối bảo vệ try { ... } finally { MDC.clear() }                         │
└─────────────────────────────────────────┬──────────────────────────────────────────────┘
                                          │
                                          ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 2. TẦNG ĐIỀU PHỐI (BankingAgentController)                                             │
│    ├─ Log incoming request kèm [trace_id]                                              │
│    └─ Chuyển tiếp Request DTO sang BankingAgentService.processBankingIntent()          │
└─────────────────────────────────────────┬──────────────────────────────────────────────┘
                                          │
                                          ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 3. TẦNG QUẢN LÝ PROMPT TẬP TRUNG (PromptRegistryService)                               │
│    ├─ Kiểm tra In-Memory Cache (Caffeine - TTL 60s)                                   │
│    │    ├─ [HIT]  ──► Trả về template (~0ms)                                          │
│    │    └─ [MISS] ──► Remote fetch từ Langfuse Server (Label: production)              │
│    │                  └─ (Nếu Langfuse Offline ──► Tự động dùng DEFAULT_FALLBACK_PROMPT)│
│    └─ Compile biến động: {{user_name}}, {{current_balance}} ...                        │
└─────────────────────────────────────────┬──────────────────────────────────────────────┘
                                          │
                                          ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 4. TẦNG THỰC THI AI & TỰ PHỤC HỒI (Resilient Multi-LLM Engine)                         │
│                                                                                        │
│    ┌──────────────────────────────────────────────────────────────────────────────┐    │
│    │ [PRIMARY ROUTE] Gọi Cloud LLM (OpenRouter / gemini-2.5-flash)                │    │
│    │ ── Thử nghiệm tối đa 3 lần (Retry with Exponential Backoff)                   │    │
│    └──────────────────────┬───────────────────────────────────────────────────────┘    │
│                           │                                                            │
│             ┌─────────────┴─────────────┐                                              │
│      [SUCCESS 200 OK]             [THẤT BẠI: HTTP 429/503/Timeout]                     │
│             │                                   │ (Kích hoạt Failover)                 │
│             │                                   ▼                                      │
│             │                     ┌──────────────────────────────────────────────┐     │
│             │                     │ [FAILOVER ROUTE] Gọi Local LLM (Ollama)      │     │
│             │                     │ ── Model: qwen2.5-coder / llama3 (On-premise)│     │
│             │                     │ ── Độ trễ mạng = 0ms, hoạt động Offline 100% │     │
│             │                     └──────────────────────┬───────────────────────┘     │
│             │                                            │                             │
│             └─────────────────────┬──────────────────────┘                             │
│                                   ▼                                                    │
│                [ Bóc tách JSON -> TransactionDetails DTO ]                             │
└───────────────────────────────────┬────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 5. TẦNG TÍNH TOÁN CHI PHÍ & ĐỐI SOÁT (LlmCostCalculator)                               │
│    ├─ Trích xuất: inputTokens & outputTokens từ ChatResponse                           │
│    ├─ Tính toán: BigDecimal với RoundingMode.HALF_UP                                   │
│    │    ├─ Cloud LLM: Rate $0.075 / $0.300 per 1M tokens                               │
│    │    └─ Local Ollama: Chi phí = $0.00000000 (Internal Compute)                      │
│    └─ Định dạng format: "$0.00012345"                                                  │
└───────────────────────────────────┬────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 6. TẦNG QUẢN TRỊ TELEMETRY (Langfuse OTLP Tracing)                                     │
│    ├─ Đóng gói Span: Input, Output, LatencyMs, Cost (BigDecimal), ModelUsed           │
│    ├─ Gắn Metadata: { "department": "FINANCE", "userId": "USR_999", "env": "prod" }    │
│    └─ Đẩy qua BatchSpanProcessor (Non-blocking queue, phòng thủ Drop Span)            │
└───────────────────────────────────┬────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 7. PHẢN HỒI CLIENT (BankingApiResponse)                                                │
│    └─ JSON Output: { "status": "SUCCESS", "details": {...}, "traceId": "...",          │
│                      "modelUsed": "gemini-2.5-flash", "cost": "$0.00001200" }          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. THUYẾT MINH GIẢI PHÁP KIẾN TRÚC CHI TIẾT

Hệ thống được thiết kế theo 5 nguyên lý kiến trúc phòng thủ và quan sát:

### 3.1. Cơ chế Tự Phục Hồi (Self-Healing Failover Engine)
Trong môi trường tài chính, việc gọi Cloud LLM tiềm ẩn 3 rủi ro gián đoạn:
1. **HTTP 429 Too Many Requests:** Bị thắt nút băng thông hoặc cạn hạn mức API quota của nhà cung cấp.
2. **HTTP 503 / 500 Service Outage:** Máy chủ OpenAI/OpenRouter gặp sự cố hạ tầng.
3. **Network Latency / Timeout:** Đứt cáp quang biển hoặc nghẽn mạng quốc tế.

**Giải pháp triển khai:**
- **Retry Pattern:** Sử dụng cơ chế Retry (tối đa 3 lần với khoảng thời gian chờ tăng dần - Exponential Backoff) để xử lý các lỗi mạng tạm thời (transient errors).
- **Circuit Breaker / Failover:** Khi vượt quá số lần retry hoặc gặp lỗi nghiêm trọng, hệ thống tự động bắt ngoại lệ và điều hướng luồng thực thi sang **Ollama Chat Client** chạy trên hạ tầng mạng nội bộ (On-premise / Local GPU). 
- **Đảm bảo tính liên tục (Business Continuity):** Khách hàng vẫn thực hiện được giao dịch trơn tru mà không hề nhận thấy sự cố gián đoạn từ phía nhà cung cấp Cloud AI.

### 3.2. Quản lý Vòng đời Ngữ cảnh & Phân tán TraceId (SLF4J MDC + OpenTelemetry)
- Toàn bộ chu trình request được bọc bởi `TraceMdcFilter` (kế thừa `OncePerRequestFilter`).
- Ngay khi request đi vào ứng dụng, Filter trích xuất `TraceId` từ OpenTelemetry `SpanContext` và nạp vào SLF4J MDC với khóa `trace_id`.
- Mọi câu lệnh log từ Filter, Controller, Service cho đến Calculator đều tự động in mã `[%X{trace_id}]`.
- Khối `try-finally` đảm bảo 100% `MDC.clear()` được gọi khi kết thúc request, triệt tiêu rủi ro **Context Pollution** (nhiễm chéo dữ liệu giữa các luồng) và **Memory Leak** trong Tomcat Thread Pool.

### 3.3. Quản lý Prompt Tập Trung với Cache TTL & Fallback
- Prompt nghiệp vụ được lưu trữ trên **Langfuse Prompt Registry**, cho phép thay đổi phiên bản (Versioning) và điều chỉnh quy tắc chuyển tiền mà không cần sửa code hay redeploy hệ thống.
- **In-Memory Cache (Caffeine TTL = 60s):** Lưu trữ template trên RAM cục bộ, giúp thời gian lấy prompt đạt ~0ms, giảm tải 99.9% lưu lượng HTTP sang Langfuse.
- **Defensive Fallback Prompt:** Nếu máy chủ Langfuse ngoại tuyến, hệ thống tự động dùng bản prompt dự phòng (`DEFAULT_BANKING_PROMPT`) trong mã nguồn Java, đảm bảo tính sẵn sàng 100%.

### 3.4. Kiểm toán Chi Phí Chính Xác Tuyệt Đối (`BigDecimal`)
- Sử dụng `BigDecimal` với `RoundingMode.HALF_UP` cho toàn bộ các phép tính chia và nhân đơn giá token vi phân ($0.075 / 1M input, $0.300 / 1M output).
- Ngăn ngừa hoàn toàn sai số làm tròn của chuẩn số thực dấu phẩy động nhị phân IEEE 754 (`float`/`double`).
- Khi chuyển sang chế độ Failover (Local Ollama), chi phí tính toán được gán cố định là `$0.00000000` do sử dụng tài nguyên phần cứng nội bộ.

### 3.5. Giám sát LLMOps Bất Đồng Bộ Kháng Nghẽn (Non-blocking Batch Processor)
- Dữ liệu Trace, Metadata, Latency và Cost được gửi về Langfuse thông qua OpenTelemetry OTLP Exporter.
- Sử dụng **BatchSpanProcessor** với hàng đợi giới hạn (**Bounded Queue `max-queue-size: 2048`**) và cơ chế **Drop Span** khi quá tải.
- Đảm bảo việc giám sát không bao giờ trở thành điểm nghẽn (Single Point of Bottleneck) làm chậm hoặc treo luồng giao dịch ngân hàng cốt lõi.

---

## 4. ĐẶC TẢ CÁC ĐỐI TƯỢNG TRUYỀN DỮ LIỆU (DTOs SPECIFICATION)

### 4.1. Input Request DTO (`BankingTransferRequest.java`)
```json
{
  "message": "Chuyển 500000 VND cho Nguyen Van B tai Vietcombank noi dung tra tien an",
  "userId": "USR_999",
  "department": "FINANCE"
}
```

### 4.2. Bóc Tách Thực Thể Giao Dịch (`TransactionDetails.java`)
```json
{
  "sender": "USR_999",
  "receiver": "Nguyen Van B",
  "bankCode": "VCB",
  "amount": 500000,
  "description": "tra tien an",
  "status": "SUCCESS"
}
```

### 4.3. Output Response DTO (`BankingApiResponse.java`)
```json
{
  "status": "SUCCESS",
  "message": "Trích xuất thông tin giao dịch thành công",
  "data": {
    "sender": "USR_999",
    "receiver": "Nguyen Van B",
    "bankCode": "VCB",
    "amount": 500000,
    "description": "tra tien an",
    "status": "SUCCESS"
  },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "latencyMs": 420,
  "estimatedCost": "$0.00001530",
  "modelUsed": "gemini-2.5-flash",
  "failoverTriggered": false
}
```

---

## 5. KỊCH BẢN VẬN HÀNH THỰC TẾ & MINH CHỨNG LOG CONSOLE

### Kịch bản 1: Hoạt động bình thường qua Cloud LLM (OpenRouter / Gemini)
```text
2026-08-25 15:30:10.100 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.f.TraceMdcFilter - Incoming Request: POST /api/v1/banking/process from User: USR_999
2026-08-25 15:30:10.105 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.s.PromptRegistryService - Cache Hit for 'banking_extract_prompt'! (0ms)
2026-08-25 15:30:10.110 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.s.BankingAgentService - Invoking Primary Cloud LLM (gemini-2.5-flash)...
2026-08-25 15:30:10.520 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.s.BankingAgentService - Cloud LLM Response received. Tokens: [Input: 145, Output: 42]
2026-08-25 15:30:10.525 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.u.LlmCostCalculator - Calculated Cost: $0.00002348 (department: FINANCE)
2026-08-25 15:30:10.530 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.f.TraceMdcFilter - Request finished successfully in 430ms. MDC cleared.
```

### Kịch bản 2: Cloud LLM gặp sự cố (HTTP 429) $ightarrow$ Tự phục hồi Failover sang Local Ollama
```text
2026-08-25 15:32:00.200 [http-nio-8080-exec-3] INFO  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.f.TraceMdcFilter - Incoming Request: POST /api/v1/banking/process from User: USR_999
2026-08-25 15:32:00.205 [http-nio-8080-exec-3] INFO  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.s.BankingAgentService - Invoking Primary Cloud LLM (gemini-2.5-flash)...
2026-08-25 15:32:01.010 [http-nio-8080-exec-3] WARN  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.s.BankingAgentService - Cloud LLM Attempt 1 failed: HTTP 429 Too Many Requests. Retrying (1/3)...
2026-08-25 15:32:02.015 [http-nio-8080-exec-3] WARN  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.s.BankingAgentService - Cloud LLM Attempt 2 failed: HTTP 429 Too Many Requests. Retrying (2/3)...
2026-08-25 15:32:03.020 [http-nio-8080-exec-3] ERROR [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.s.BankingAgentService - Cloud LLM exceeded max retries! KÍCH HOẠT FAILOVER SANG LOCAL OLLAMA...
2026-08-25 15:32:03.025 [http-nio-8080-exec-3] INFO  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.s.BankingAgentService - Invoking Local LLM (qwen2.5-coder via Ollama @ localhost:11434)...
2026-08-25 15:32:03.680 [http-nio-8080-exec-3] INFO  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.s.BankingAgentService - Local LLM extraction SUCCESS. Extracted: {receiver: 'Nguyen Van B', amount: 500000, bank: 'VCB'}
2026-08-25 15:32:03.685 [http-nio-8080-exec-3] INFO  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.u.LlmCostCalculator - Local Model Used -> Cost: $0.00000000
2026-08-25 15:32:03.690 [http-nio-8080-exec-3] INFO  [8a1c3d9021b44fa7b5e1929d0e0e9981] c.r.a.f.TraceMdcFilter - Request handled with FAILOVER in 3490ms. Response returned 200 OK. MDC cleared.
```

---

## 6. KẾT LUẬN

Giải pháp kiến trúc khép kín cho **Banking AI Agent** tại RikkeiPay mang lại 3 giá trị cốt lõi:
1. **Khả năng tự phục hồi vượt trội:** Triệt tiêu hoàn toàn rủi ro phụ thuộc vào một nhà cung cấp AI duy nhất thông qua cơ chế Failover sang Local Ollama.
2. **Tuân thủ chuẩn mực tài chính:** Đảm bảo độ chính xác tuyệt đối trong tính toán và kiểm toán chi phí với `BigDecimal`.
3. **Quan sát toàn diện (End-to-End Observability):** Kết hợp chặt chẽ giữa OpenTelemetry Tracing, SLF4J MDC Logging và Langfuse LLMOps Platform tạo nên hạ tầng vững chắc, sẵn sàng vận hành ổn định trên môi trường Production.


2026-08-25T19:40:12.102+07:00 [trace_id=4c88a1b9f7124e3ba91] INFO 18420 --- [nio-8080-exec-1] c.e.b.c.BankingAgentController          : [REQUEST RECEIVED] Processing natural language transfer for user: USR_999
2026-08-25T19:40:12.105+07:00 [trace_id=4c88a1b9f7124e3ba91] INFO 18420 --- [nio-8080-exec-1] c.e.b.s.PromptRegistryService         : [CACHE HIT] Loaded prompt 'banking_extraction_prompt' from local cache in 0 ms
2026-08-25T19:40:12.108+07:00 [trace_id=4c88a1b9f7124e3ba91] INFO 18420 --- [nio-8080-exec-1] c.e.b.s.BankingAgentService           : [PRIMARY CLOUD] Calling OpenRouter (google/gemini-2.5-flash)
2026-08-25T19:40:12.630+07:00 [trace_id=4c88a1b9f7124e3ba91] INFO 18420 --- [nio-8080-exec-1] c.e.b.s.BankingAgentService           : [TELEMETRY] TraceId: 4c88a1b9f7124e3ba91, Model: google/gemini-2.5-flash, Cost: $0.000023, Latency: 522 ms, Status: SUCCESS
2026-08-25T19:40:12.632+07:00 [trace_id=4c88a1b9f7124e3ba91] INFO 18420 --- [nio-8080-exec-1] c.e.b.c.BankingAgentController          : [RESPONSE DISPATCHED] HTTP 200 OK - Transfer details extracted successfully

2026-08-25T19:42:05.310+07:00 [trace_id=e703f8a01cd24b5ca33] INFO 18420 --- [nio-8080-exec-3] c.e.b.c.BankingAgentController          : [REQUEST RECEIVED] Processing natural language transfer for user: USR_999
2026-08-25T19:42:05.312+07:00 [trace_id=e703f8a01cd24b5ca33] INFO 18420 --- [nio-8080-exec-3] c.e.b.s.PromptRegistryService         : [CACHE HIT] Loaded prompt 'banking_extraction_prompt' from local cache in 0 ms
2026-08-25T19:42:05.314+07:00 [trace_id=e703f8a01cd24b5ca33] INFO 18420 --- [nio-8080-exec-3] c.e.b.s.BankingAgentService           : [PRIMARY CLOUD] Calling OpenRouter (google/gemini-2.5-flash)
2026-08-25T19:42:06.120+07:00 [trace_id=e703f8a01cd24b5ca33] WARN 18420 --- [nio-8080-exec-3] c.e.b.s.BankingAgentService           : [RETRY 1/3] OpenRouter error: 429 Too Many Requests: Rate limit exceeded
2026-08-25T19:42:07.250+07:00 [trace_id=e703f8a01cd24b5ca33] WARN 18420 --- [nio-8080-exec-3] c.e.b.s.BankingAgentService           : [RETRY 2/3] OpenRouter error: 429 Too Many Requests: Rate limit exceeded
2026-08-25T19:42:08.890+07:00 [trace_id=e703f8a01cd24b5ca33] WARN 18420 --- [nio-8080-exec-3] c.e.b.s.BankingAgentService           : [RETRY 3/3] OpenRouter error: 429 Too Many Requests: Rate limit exceeded
2026-08-25T19:42:08.892+07:00 [trace_id=e703f8a01cd24b5ca33] ERROR 18420 --- [nio-8080-exec-3] c.e.b.s.BankingAgentService          : [FAILOVER ACTIVATED] Primary LLM failed: Cloud LLM max retries reached. Switching to Local Ollama (qwen2.5:latest)
2026-08-25T19:42:10.450+07:00 [trace_id=e703f8a01cd24b5ca33] INFO 18420 --- [nio-8080-exec-3] c.e.b.s.BankingAgentService           : [TELEMETRY] TraceId: e703f8a01cd24b5ca33, Model: qwen2.5:latest, Cost: $0.000000, Latency: 5138 ms, Status: SUCCESS
2026-08-25T19:42:10.452+07:00 [trace_id=e703f8a01cd24b5ca33] INFO 18420 --- [nio-8080-exec-3] c.e.b.c.BankingAgentController          : [RESPONSE DISPATCHED] HTTP 200 OK - Transfer details recovered via Local Fallback