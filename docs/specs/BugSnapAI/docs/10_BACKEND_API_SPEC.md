# 10 — Đặc tả Backend API (FastAPI)

## 1. Vai trò và nguyên tắc
Backend **mỏng**: giữ khóa AI, dựng prompt, gọi model, validate JSON, kiểm soát ngân sách. **Không lưu ảnh/nội dung** sau khi trả kết quả (stateless; chỉ log metadata: request id, model, token, chi phí, trạng thái).
Lý do tồn tại: không nhúng khóa AI vào APK. Phương án thay thế cho dev đơn lẻ (BYO key trong Keystore, gọi thẳng) được ghi ở `16` nhưng **không phải mặc định**.

## 2. Cấu trúc thư mục
```text
backend/
├── app/
│   ├── main.py            # tạo FastAPI app, include router
│   ├── config.py          # đọc env (pydantic-settings)
│   ├── auth.py            # kiểm tra bearer token
│   ├── routers/reports.py, routers/health.py
│   ├── schemas/           # request/response Pydantic
│   ├── services/prompt.py, services/budget.py, services/validate.py
│   └── providers/base.py, providers/openai_compat.py
├── tests/
├── requirements.txt
└── .env.example           # không chứa secret thật
```
Mỗi file ≤ 500 dòng (theo `AGENTS.md`).

## 3. Endpoint
| Method | Path | Mục đích |
|---|---|---|
| GET | `/v1/health` | Kiểm tra sống, phiên bản schema, `vision_supported` |
| POST | `/v1/reports:generate` | Sinh bản nháp report từ payload đã duyệt |
| GET | `/v1/usage` | Chi phí ước tính tháng/ngày và trần |

### POST `/v1/reports:generate`
- `multipart/form-data`: phần `payload` (JSON, §4 của `08`) + các phần `frame_<id>` (JPEG). Giới hạn: tổng ≤ 6 MB, ≤ 8 ảnh, mỗi ảnh ≤ 800 KB.
- Header: `Authorization: Bearer <token>`, `X-Request-Id: <uuid>` (idempotency tối thiểu).
- **200** → `{ "request_id", "report": <schema §5 của 08>, "usage": {"input_tokens","output_tokens","cost_estimate_usd"}, "mode": "vision|text_only", "prompt_version" }`
- Lỗi: JSON `{ "error": {"code","message","retry_after_s?"} }`

| HTTP | `code` | Ý nghĩa |
|---|---|---|
| 400 | `INVALID_PAYLOAD` | Sai schema/ảnh quá lớn |
| 401/403 | `UNAUTHORIZED` | Token sai/hết hạn |
| 413 | `PAYLOAD_TOO_LARGE` | |
| 422 | `AI_SCHEMA_INVALID` | Model trả JSON không hợp lệ sau 1 lần sửa |
| 429 | `RATE_LIMITED` / `BUDGET_EXCEEDED` | Rate limit hoặc chạm trần chi phí |
| 502 | `AI_PROVIDER_ERROR` | Lỗi nhà cung cấp |
| 504 | `AI_TIMEOUT` | Quá thời gian (mặc định 60 s) |

## 4. Abstraction nhà cung cấp AI
```text
class AiProvider(Protocol):
    async def generate(self, system: str, user_json: dict, images: list[bytes], schema: dict) -> RawResult
```
- Một triển khai `OpenAICompatibleProvider` (base URL, API key, tên model từ env). Thêm nhà cung cấp khác = thêm class, không đổi router.
- Cấu hình env: `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`, `AI_SUPPORTS_VISION`, `AI_TIMEOUT_S`, `MONTHLY_BUDGET_USD`, `DAILY_BUDGET_USD`, `API_TOKENS` (danh sách băm).
- Dùng structured output (`json_schema`) nếu provider hỗ trợ; nếu không dùng `json_object` + validate. Luôn validate bằng Pydantic.
- `AI_SUPPORTS_VISION=false` ⇒ bỏ ảnh, chạy chế độ `text_only`, trả `mode` để app báo tester.

## 5. Xác thực và bảo mật
- Bearer token **theo thiết bị**, lưu băm ở server, thu hồi được. Tối thiểu cho MVP: một danh sách token trong env.
- TLS bắt buộc. Rate limit theo token (ví dụ 20 req/phút). Giới hạn kích thước body ở reverse proxy.
- Không log ảnh, ghi chú, transcript. Log có `request_id`, thời lượng, token.
- Khóa AI chỉ nằm ở biến môi trường server.

## 6. Ngân sách
`services/budget.py`: cộng dồn chi phí ước tính vào bộ đếm (file/SQLite nhỏ). Vượt trần ⇒ `BUDGET_EXCEEDED` **trước khi** gọi model. Bảng giá model cấu hình bằng env/JSON, không hardcode.

## 7. Triển khai
Một container nhỏ (uvicorn) trên host rẻ, hoặc chạy trên MacBook của dev trong lúc phát triển (app gọi qua mạng LAN/tunnel có TLS). Không cần DB, queue, cache ở MVP.

## 8. Hợp đồng phía Android
`AiReportClient.generate(ApprovedPayload)` ánh xạ 1-1 `/v1/reports:generate`; map mã lỗi → `DomainError` (xem `05` §8). Timeout client = timeout server + 10 s. Retry chỉ cho 502/504/mất mạng, không cho 4xx.

## 9. Test backend
Unit: validate schema, từ chối payload quá lớn, budget guard, text_only khi không có vision. Contract test với provider giả (mock). Không gọi API thật trong CI.
