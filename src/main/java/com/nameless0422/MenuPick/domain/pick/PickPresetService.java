package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.domain.VersionGuard;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 상황별 빠른 픽 — 저장형 프리셋의 CRUD와 실행({@code docs/PickPresetDesign.md}).
 *
 * <h2>실행이 별도 경로인 이유</h2>
 *
 * <p>일반 {@code POST /pick}에 "프리셋 id"를 얹지 않고 전용 엔드포인트를 둔다. 프리셋 실행은
 * 검증할 것이 다르기 때문이다 — 버전 확인, 검토 상태, 태그 소유권 재확인, 기본 제외와의
 * 합집합, 그 합집합의 상한. 이걸 일반 경로에 섞으면 <b>가장 많이 도는 경로에 프리셋용
 * 분기가 얹힌다.</b>
 *
 * <p>대신 여기서 조립한 신뢰할 수 있는 {@link PickRequest}로 기존 {@link PickService}를
 * 그대로 부른다. 추천 로직을 복제하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PickPresetService {

    /**
     * 실행 시 적용되는 제외 태그(기본 + 추가)의 상한.
     *
     * <p>일반 {@code PickRequest}의 명시적 제외 상한 20과 <b>다른 값이고 그래야 한다.</b>
     * 20은 사용자가 한 화면에서 직접 고르는 개수의 상한이고, 50은 여기서 합쳐진 결과의
     * 상한이다. 일반 경로의 20을 느슨하게 만들지 않는다 — 이 경로가 검증을 마친 합성
     * 요청만 50까지 허용한다.
     *
     * <p>넘으면 <b>자르지 않고 멈춘다.</b> 잘라 내면 사용자가 빼 두라고 한 태그가 조용히
     * 다시 들어와 추천된다 — 알레르기 태그라면 특히 그렇다.
     */
    public static final int MAX_EFFECTIVE_EXCLUDE_TAGS = 50;

    private final PickPresetRepository presetRepository;
    private final TagRepository tagRepository;
    private final UserRepository userRepository;
    private final DefaultPickPreferenceService defaultPickPreferenceService;
    private final PickService pickService;

    // ── 조회 ────────────────────────────────────────────────────────────

    public PickPresetResponse.ListResult list(Long userId) {
        List<PickPresetResponse.Detail> presets =
                presetRepository.findAllByUserIdWithConditions(userId).stream()
                        .map(PickPresetResponse.Detail::from)
                        .toList();
        return PickPresetResponse.ListResult.of(presets);
    }

    public PickPresetResponse.Detail get(Long userId, Long presetId) {
        return PickPresetResponse.Detail.from(load(userId, presetId));
    }

    // ── 생성 ────────────────────────────────────────────────────────────

    @Transactional
    public PickPresetResponse.Detail create(Long userId, PickPresetRequest.Create request) {
        String name = normalizeName(request.name());
        Set<String> categories = normalizeCategories(request.categories());
        Set<Long> includeIds = normalizeTagIds(request.includeTagIds());
        Set<Long> excludeIds = normalizeTagIds(request.additionalExcludeTagIds());

        validateConditions(userId, request.maxDistance(), includeIds, excludeIds);

        // 사용자 행을 먼저 잠근다. 세면서 잠그는 방식(SELECT COUNT(*) ... FOR UPDATE)은
        // 이미 있는 행만 잠그고 새 INSERT를 막지 못해 경쟁이 그대로 남는다 — 실제로 그렇게
        // 짰다가 동시 20건에서 12건이 저장됐다. 근거는 UserRepository.findByIdForUpdate.
        // 인증을 통과했으므로 행은 있다. 없다면 그 사이 탈퇴 purge가 지난 것이라
        // 계속 진행할 근거가 없다.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (presetRepository.countByUserId(userId) >= PickPreset.MAX_PER_USER) {
            throw new BusinessException(ErrorCode.PICK_PRESET_LIMIT_EXCEEDED);
        }
        // 일반 경로에서 명확한 에러를 주기 위한 사전 검사. 동시 요청 레이스는 아래 catch가 받는다.
        if (presetRepository.existsByUserIdAndName(userId, name)) {
            throw new BusinessException(ErrorCode.PICK_PRESET_NAME_DUPLICATE);
        }

        PickPreset preset = PickPreset.builder()
                .user(userRepository.getReferenceById(userId))
                .name(name)
                .maxDistance(request.maxDistance())
                .build();
        preset.replaceConditions(name, request.maxDistance(), categories, toTags(includeIds, excludeIds));

        try {
            return PickPresetResponse.Detail.from(presetRepository.saveAndFlush(preset));
        } catch (DataIntegrityViolationException e) {
            // uq_pick_presets_user_name — check-then-act 사이에 끼어든 동시 생성
            throw new BusinessException(ErrorCode.PICK_PRESET_NAME_DUPLICATE);
        }
    }

    // ── 수정 (전체 교체) ────────────────────────────────────────────────

    @Transactional
    public PickPresetResponse.Detail update(Long userId, Long presetId,
                                            PickPresetRequest.Update request) {
        PickPreset preset = load(userId, presetId);
        VersionGuard.requireCurrentVersion(preset.getVersion(), request.version());

        String name = normalizeName(request.name());
        Set<String> categories = normalizeCategories(request.categories());
        Set<Long> includeIds = normalizeTagIds(request.includeTagIds());
        Set<Long> excludeIds = normalizeTagIds(request.additionalExcludeTagIds());

        validateConditions(userId, request.maxDistance(), includeIds, excludeIds);

        // 검토가 필요한 상태는 명시적 승인 없이 풀리지 않는다. 승인은 "지금 이 조건이 맞다"는
        // 확인이므로, 조건 검증을 모두 통과한 뒤에야 의미가 있다.
        if (preset.isNeedsReview()) {
            if (!Boolean.TRUE.equals(request.acknowledgeRemovedTags())) {
                throw new BusinessException(ErrorCode.PICK_PRESET_NEEDS_REVIEW);
            }
            preset.clearNeedsReview();
        }

        if (!preset.getName().equals(name) && presetRepository.existsByUserIdAndName(userId, name)) {
            throw new BusinessException(ErrorCode.PICK_PRESET_NAME_DUPLICATE);
        }

        preset.replaceConditions(name, request.maxDistance(), categories,
                toTags(includeIds, excludeIds));
        try {
            presetRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.PICK_PRESET_NAME_DUPLICATE);
        }
        return PickPresetResponse.Detail.from(preset);
    }

    // ── 삭제 ────────────────────────────────────────────────────────────

    @Transactional
    public void delete(Long userId, Long presetId, Long version) {
        PickPreset preset = load(userId, presetId);
        VersionGuard.requireCurrentVersion(preset.getVersion(), version);
        presetRepository.delete(preset);
    }

    // ── 실행 ────────────────────────────────────────────────────────────

    /**
     * 저장된 조건으로 즉시 픽한다.
     *
     * <p>읽기가 아니라 <b>쓰기 트랜잭션</b>이다 — 픽은 히스토리를 남긴다. 그리고 버전 확인만으로는
     * 동시 수정과 태그 무효화를 막을 수 없어({@code read}와 {@code use} 사이가 벌어진다)
     * 프리셋 행을 잠근 뒤 확인한다.
     *
     * <p>검증에 걸리면 히스토리는 하나도 생기지 않고, 통과하면 정확히 하나 생긴다.
     */
    @Transactional
    public PickPresetResponse.ExecutionResult execute(Long userId, Long presetId,
                                                      PickPresetRequest.Execute request) {
        // 잠금 조회다. 버전 확인과 실제 픽 사이에 태그 삭제가 끼어들어 검사를 우회하는 것을
        // 막는다 — 근거는 PickPresetRepository.findByIdAndUserIdForUpdate.
        PickPreset preset = presetRepository.findByIdAndUserIdForUpdate(presetId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PICK_PRESET_NOT_FOUND));
        VersionGuard.requireCurrentVersion(preset.getVersion(), request.version());

        if (preset.isNeedsReview()) {
            throw new BusinessException(ErrorCode.PICK_PRESET_NEEDS_REVIEW);
        }

        Set<Long> includeIds = preset.includeTagIds();
        Set<Long> additionalExcludeIds = preset.excludeTagIds();

        // 참조 태그가 아직 내 것인지 다시 본다. FK cascade가 지워 주지만, 소유권이 옮겨 가는
        // 경로는 없어도 "지금도 유효한가"를 실행 시점에 확인하는 편이 안전하다.
        requireOwnedTags(userId, union(includeIds, additionalExcludeIds));

        // 기본 제외는 저장돼 있지 않다. 여기서 정확히 한 번 읽어 최신 값을 합친다.
        Set<Long> effectiveExclude = union(
                defaultPickPreferenceService.getDefaultExcludedTagIds(userId),
                additionalExcludeIds);

        // 포함과 제외가 겹치면 후보가 반드시 0이 된다. "결과 없음"이 아니라 조건 모순이므로
        // 409로 알린다 — 대개 기본 제외에 새로 넣은 태그가 프리셋의 포함과 부딪힌 경우다.
        Set<Long> conflict = intersection(includeIds, effectiveExclude);
        if (!conflict.isEmpty()) {
            throw new BusinessException(ErrorCode.PICK_PRESET_TAG_CONFLICT);
        }

        if (effectiveExclude.size() > MAX_EFFECTIVE_EXCLUDE_TAGS) {
            throw new BusinessException(ErrorCode.PICK_PRESET_EXCLUDE_LIMIT);
        }

        BigDecimal latitude = request.latitude();
        BigDecimal longitude = request.longitude();
        requireCoordinatesMatchDistance(preset.getMaxDistance(), latitude, longitude);

        // excludeTagIds를 **명시적으로** 넣는다. PickService는 이 값이 null일 때만 기본 제외를
        // 조회하므로, 완성된 합집합을 주면 같은 조회가 두 번 나가지 않는다.
        PickRequest synthesized = new PickRequest(
                preset.getCategories().isEmpty() ? null : Set.copyOf(preset.getCategories()),
                includeIds.isEmpty() ? null : includeIds,
                effectiveExclude,
                latitude,
                longitude,
                preset.getMaxDistance());

        return new PickPresetResponse.ExecutionResult(
                pickService.pick(userId, synthesized),
                new PickPresetResponse.AppliedFilters(
                        Set.copyOf(preset.getCategories()),
                        includeIds,
                        effectiveExclude,
                        preset.getMaxDistance()));
    }

    // ── 검증 도우미 ─────────────────────────────────────────────────────

    private PickPreset load(Long userId, Long presetId) {
        return presetRepository.findByIdAndUserIdWithConditions(presetId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PICK_PRESET_NOT_FOUND));
    }

    private void validateConditions(Long userId, Integer maxDistance,
                                    Set<Long> includeIds, Set<Long> excludeIds) {
        if (!PickPresetDistance.isValid(maxDistance)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        // 저장 시점의 직접 충돌은 400이다 — 사용자가 방금 고른 두 값이 모순이라 고칠 수 있다.
        // (실행 시점의 기본 제외와의 충돌은 409다. 그건 저장 뒤에 생긴 상황이다.)
        if (!intersection(includeIds, excludeIds).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        requireOwnedTags(userId, union(includeIds, excludeIds));
    }

    /**
     * 태그가 모두 이 사용자 것인지 확인한다. 아니면 <b>404</b>다 —
     * 403으로 답하면 "그 id는 존재한다"는 사실이 새어 나간다(기존 태그·메뉴 조회와 같은 관례).
     */
    private void requireOwnedTags(Long userId, Set<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return;
        }
        if (tagRepository.findAllByIdInAndUserId(tagIds, userId).size() != tagIds.size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
    }

    /**
     * 거리 조건과 좌표는 함께 와야 한다.
     *
     * <p>거리를 저장해 둔 프리셋을 좌표 없이 실행하면 <b>거리 없는 픽으로 조용히 폴백하지
     * 않는다.</b> 사용자는 "가까운 곳"을 기대했는데 전혀 다른 결과를 받게 되기 때문이다.
     * 반대로 거리 조건이 없는데 좌표를 보내는 것도 받지 않는다 — 쓰이지 않는 위치를
     * 서버로 보내는 셈이라 개인정보 측면에서도 받을 이유가 없다.
     */
    private void requireCoordinatesMatchDistance(Integer maxDistance,
                                                 BigDecimal latitude, BigDecimal longitude) {
        boolean hasCoordinates = latitude != null && longitude != null;
        boolean partialCoordinates = (latitude == null) != (longitude == null);
        if (partialCoordinates) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (maxDistance != null && !hasCoordinates) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (maxDistance == null && hasCoordinates) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > PickPreset.NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return name;
    }

    /** 저장 경로·픽 경로와 같은 정규화 — 앞뒤 공백만 다른 값이 다른 카테고리가 되지 않게 한다. */
    private static Set<String> normalizeCategories(Set<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Long> normalizeTagIds(Set<Long> raw) {
        if (raw == null) {
            return Set.of();
        }
        // raw.contains(null)을 쓰지 않는다 — Set.of(...)가 만든 불변 집합은 null을 물으면
        // NPE를 던진다(ImmutableCollections.SetN.contains). 원소를 직접 훑는다.
        if (raw.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new LinkedHashSet<>(raw);
    }

    private static Set<PickPresetTag> toTags(Set<Long> includeIds, Set<Long> excludeIds) {
        Set<PickPresetTag> tags = new LinkedHashSet<>();
        includeIds.forEach(id -> tags.add(PickPresetTag.include(id)));
        excludeIds.forEach(id -> tags.add(PickPresetTag.exclude(id)));
        return tags;
    }

    private static Set<Long> union(Set<Long> a, Set<Long> b) {
        Set<Long> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static Set<Long> intersection(Set<Long> a, Set<Long> b) {
        Set<Long> result = new LinkedHashSet<>(a);
        result.retainAll(b);
        return result;
    }
}
