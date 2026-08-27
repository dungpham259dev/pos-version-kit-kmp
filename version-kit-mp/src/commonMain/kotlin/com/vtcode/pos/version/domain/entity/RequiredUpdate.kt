package com.vtcode.pos.version.domain.entity

/**
 * Bản cập nhật bắt buộc cao nhất trong danh sách, hoặc null nếu không có.
 *
 * Danh sách truyền vào đã được [com.vtcode.pos.version.domain.service.IVersionService.shouldPresentUpdate]
 * lọc, nên mọi phần tử ở đây đều mới hơn version hiện tại và tải được. Hệ quả có chủ ý:
 * một version bật `is_required` nhưng thiếu file sẽ không bao giờ tới được đây, nên không
 * khoá được app.
 */
fun List<UpdateInfo>.requiredUpdate(): UpdateInfo? =
    filter { it.isRequired }
        .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
