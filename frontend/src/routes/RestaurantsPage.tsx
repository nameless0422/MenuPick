import { useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createRestaurant,
  deleteRestaurant,
  fetchRestaurant,
  fetchRestaurants,
  updateRestaurant,
  type RestaurantDetail,
  type RestaurantSummary,
} from "../api/restaurants";
import { searchPlacesByKeyword, type KakaoPlace } from "../api/places";
import { createMenuRestaurant } from "../api/menuRestaurants";
import { fetchMenus } from "../api/menus";
import { apiErrorMessage as errorMessage } from "../api/http";
import { chipToggle } from "../a11y/chipToggle";
import { starToggle } from "../a11y/starToggle";
import { useFocusOnMount } from "../a11y/useFocusOnMount";
import KakaoMap from "../maps/KakaoMap";

export default function RestaurantsPage() {
  const queryClient = useQueryClient();

  const restaurantsQuery = useQuery({
    queryKey: ["restaurants"],
    queryFn: fetchRestaurants,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["restaurants"] });

  const restaurants = restaurantsQuery.data ?? [];

  // 삭제를 누른 버튼은 그 카드와 함께 사라진다 — window.confirm이 초점을 버튼으로 되돌려
  // 놓아도 결국 <body>로 떨어져, 다음 식당을 지우려면 페이지 맨 위에서 Tab을 다시 눌러
  // 내려와야 한다. 삭제 변이는 카드 안에 있어 카드가 스스로를 언마운트하므로, 초점을 받을
  // 자리는 카드보다 오래 사는 이쪽(부모)에 둔다. (HistoryPage와 같은 처리)
  const listRef = useRef<HTMLUListElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  // <ul>과 <h1>은 목록이 refetch로 다시 그려져도 같은 요소로 남는다 — 초점을 옮기려고
  // 렌더를 한 번 더 기다릴 이유가 없어 삭제가 성공한 그 자리에서 바로 옮긴다.
  // (폼을 닫을 때 쓰는 focusAfterClose가 state와 effect를 거치는 것은 목적지인 "수정"
  //  버튼이 다시 마운트되기를 기다려야 하기 때문이고, 여기는 그럴 필요가 없다.)
  // 목적지는 "삭제된 순간"의 개수로 정한다: 남는 식당이 있으면 <ul>이
  // 그대로 있고, 마지막 하나였으면 <ul>이 곧 사라지므로 그 전에 제목으로 빠져나와야 한다.
  const handleDeleted = () => (restaurants.length <= 1 ? headingRef : listRef).current?.focus();

  return (
    <div className="page">
      <header className="page-header">
        {/* 마지막 식당을 지우면 목록이 비어 초점을 둘 곳이 없다 — 제목이 그 폴백이다.
            제목은 원래 초점을 받지 않는 요소라 tabIndex={-1}이 없으면 focus()가 조용히
            무시된다. -1이므로 Tab 순서에는 끼지 않는다. */}
        <h1 ref={headingRef} tabIndex={-1}>내 식당</h1>
      </header>

      <PlaceSearch onSaved={invalidate} />

      <h2>저장한 식당</h2>
      {restaurantsQuery.isError && <p className="error" role="alert">{errorMessage(restaurantsQuery.error)}</p>}

      {/* 리전은 마운트 시점부터(비어 있더라도) DOM에 있어야 한다 — 내용과 함께 뒤늦게
          삽입되는 라이브 리전은 통지되지 않는다. */}
      <div role="status">
        {restaurantsQuery.isPending && <p>불러오는 중…</p>}
        {restaurantsQuery.isSuccess && restaurants.length === 0 && (
          <p>저장한 식당이 없습니다. 위에서 장소를 검색해 자주 가는 식당을 저장해 보세요.</p>
        )}
        {restaurantsQuery.isSuccess && restaurants.length > 0 && (
          <p className="sr-only">{`저장한 식당 ${restaurants.length}곳`}</p>
        )}
      </div>

      {/* 지도는 목록을 대체하지 않고 위에 얹는다 — 좌표가 없거나 지도를 못 띄우는
          상황에서도 식당을 보고 고칠 수 있어야 한다. */}
      <KakaoMap points={restaurants} ariaLabel="저장한 식당 위치" />

      {/* 0건일 때 빈 <ul>을 남기면 "목록, 항목 0개"로 읽혀 위 안내("저장한 식당이 없습니다")와
          어긋난다. Safari + VoiceOver는 list-style: none이 걸린 <ul>의 목록 시맨틱을 지우므로
          role도 명시한다. */}
      {restaurants.length > 0 && (
        <ul ref={listRef} tabIndex={-1} className="card-list" role="list">
          {restaurants.map((restaurant) => (
            <RestaurantCard
              key={restaurant.id}
              summary={restaurant}
              onChanged={invalidate}
              onDeleted={handleDeleted}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

// ---- 장소 검색 → 식당 저장 ----

function PlaceSearch({ onSaved }: { onSaved: () => void }) {
  const [keyword, setKeyword] = useState("");
  // 제출된 키워드로만 검색한다 (입력마다 카카오 프록시를 호출하지 않도록)
  const [submitted, setSubmitted] = useState("");

  const searchQuery = useQuery({
    queryKey: ["places", submitted],
    queryFn: () => searchPlacesByKeyword(submitted),
    enabled: submitted.length > 0,
  });

  const saveMutation = useMutation({
    mutationFn: (place: KakaoPlace) =>
      createRestaurant({
        name: place.place_name,
        address: place.road_address_name || place.address_name || null,
        phone: place.phone || null,
        // 카카오 좌표는 x=경도, y=위도 (문자열)
        latitude: Number(place.y),
        longitude: Number(place.x),
        naverUrl: place.place_url || null,
        kakaoPlaceId: place.id,
      }),
    onSuccess: onSaved,
  });

  const places = searchQuery.data?.documents ?? [];

  // 검색어를 비운 채 누른 뒤에만 사유를 그린다 — 화면에 들어오자마자 빈 칸을 지적할 일은
  // 아니다. 다시 채우면 사유도 함께 사라진다.
  const keywordInput = useRef<HTMLInputElement>(null);
  const keywordErrorId = useId();
  const [keywordMissing, setKeywordMissing] = useState(false);
  const showKeywordError = keywordMissing && !keyword.trim();

  // 조사(을/를)가 받침에 따라 갈리므로 이름 뒤에 "식당"을 두어 이름과 조사를 떼어 놓는다.
  const saveAnnouncement =
    saveMutation.isSuccess && saveMutation.variables
      ? saveMutation.data.created
        ? `'${saveMutation.variables.place_name}' 식당을 저장했습니다.`
        : `'${saveMutation.variables.place_name}' 식당은 이미 저장돼 있어요.`
      : "";

  return (
    <section className="menu-form">
      <h2>장소 검색으로 식당 추가</h2>
      <form
        className="inline-add"
        onSubmit={(e) => {
          e.preventDefault();
          if (searchQuery.isFetching) return;
          const trimmed = keyword.trim();
          if (!trimmed) {
            // 검색어가 없다고 알린 뒤 칠 자리로 초점을 옮긴다. 이 칸은 식당을 추가하는
            // 유일한 진입점이라, 눌러도 아무 일이 없으면 화면 전체가 막힌 것처럼 보인다.
            setKeywordMissing(true);
            keywordInput.current?.focus();
            return;
          }
          setKeywordMissing(false);
          setSubmitted(trimmed);
        }}
      >
        {/* aria-label: placeholder는 접근 가능한 이름 계산의 최후 폴백이라 읽히지 않는 구현이
            있고, 타이핑을 시작하면 화면에서도 사라진다. 이 칸은 식당을 추가하는 유일한
            진입점이라 이름이 없으면 화면 전체가 막힌다. */}
        <input
          ref={keywordInput}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          maxLength={100}
          aria-label="장소 검색어"
          aria-invalid={showKeywordError || undefined}
          aria-describedby={showKeywordError ? keywordErrorId : undefined}
          placeholder="상호명이나 지역+메뉴로 검색 (예: 역삼동 김치찌개)"
        />
        {/* 검색어 미입력으로 disabled를 걸면 이 버튼이 Tab 순회에서 빠져, 화면에 검색
            수단이 있다는 사실 자체가 전달되지 않는다. 잠근 채로 두되 초점은 남긴다. */}
        <button
          type="submit"
          aria-busy={searchQuery.isFetching}
          aria-disabled={!keyword.trim() || searchQuery.isFetching || undefined}
          aria-describedby={showKeywordError ? keywordErrorId : undefined}
        >
          {searchQuery.isFetching ? "검색 중…" : "검색"}
        </button>
      </form>
      {showKeywordError && (
        <p className="error" role="alert" id={keywordErrorId}>검색어를 입력해주세요.</p>
      )}

      {searchQuery.isError && <p className="error" role="alert">{errorMessage(searchQuery.error)}</p>}
      {saveMutation.isError && <p className="error" role="alert">{errorMessage(saveMutation.error)}</p>}

      {/* 검색을 누르면 초점은 "검색" 버튼에 남고 결과만 화면 아래에 조용히 그려진다.
          몇 건인지·0건인지·아직 도는 중인지 알 방법이 없으면 임의로 Tab을 눌러 더듬어야
          한다. 검색 → 저장이 이 화면의 핵심 플로우다. */}
      <div role="status">
        {searchQuery.isFetching && <p className="sr-only">장소를 검색하는 중…</p>}
        {searchQuery.isSuccess && !searchQuery.isFetching && places.length === 0 && (
          <p>'{submitted}' 검색 결과가 없습니다. 다른 키워드로 검색해 보세요.</p>
        )}
        {searchQuery.isSuccess && !searchQuery.isFetching && places.length > 0 && (
          <p className="sr-only">{`${places.length}건의 장소를 찾았습니다.`}</p>
        )}
      </div>

      {/* 저장 결과는 검색 리전과 분리한다 — 한 리전에 담으면 aria-atomic 때문에 저장할
          때마다 검색 건수까지 다시 읽힌다. */}
      <p role="status" className="sr-only">{saveAnnouncement}</p>

      {places.length > 0 && (
        <ul className="card-list" role="list">
          {places.map((place) => (
            <li key={place.id} className="card">
              <div className="card-main">
                <strong>{place.place_name}</strong>
                {place.category_name && <span className="chip">{place.category_name}</span>}
              </div>
              <span>{place.road_address_name || place.address_name || "주소 정보 없음"}</span>
              <div className="card-actions">
                {/* 결과가 15건이면 이름 없는 "저장"이 15개 늘어선다. 어느 가게를 저장하는
                    버튼인지는 앞의 <strong>에만 있고 버튼 이름에는 없다. */}
                <button
                  disabled={saveMutation.isPending}
                  aria-label={
                    saveMutation.isPending && saveMutation.variables === place
                      ? `${place.place_name} 저장 중`
                      : `${place.place_name} 저장`
                  }
                  onClick={() => saveMutation.mutate(place)}
                >
                  {saveMutation.isPending && saveMutation.variables === place
                    ? "저장 중…"
                    : "저장"}
                </button>
                {/* 이미 갖고 있는 장소면 서버가 새로 만들지 않고 갖고 있던 식당을 준다.
                    아무 말도 안 하면 목록이 그대로라 저장이 씹힌 것처럼 보인다.
                    여기서는 role="status"를 쓰지 않는다 — 이 <span>은 내용과 함께 처음
                    나타나므로 라이브 리전으로서는 동작하지 않고, 위의 상시 리전이 같은
                    말을 이미 통지한다. 눈으로 보는 쪽만 담당한다. */}
                {saveMutation.variables === place && saveMutation.isSuccess
                  && !saveMutation.data.created && (
                    <span>이미 저장한 식당이에요.</span>
                  )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ---- 저장한 식당 카드 ----

function RestaurantCard({
  summary,
  onChanged,
  onDeleted,
}: {
  summary: RestaurantSummary;
  onChanged: () => void;
  // 수정·연결과 달리 삭제는 이 카드를 없앤다. onChanged만으로는 부모가 그 차이를 알 수 없어
  // 초점을 옮겨야 할 때와 아닐 때가 구분되지 않으므로 삭제 경로에만 별도 신호를 준다.
  onDeleted: () => void;
}) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<"edit" | "link" | null>(null);

  // 수정 폼은 카드 전체를 대체하므로 폼을 닫으면 눌렀던 버튼이 새로 마운트된다 —
  // 초점이 <body>로 떨어지지 않도록 "어느 버튼으로 돌아갈지"를 예약해 두고, 그 버튼이
  // 다시 DOM에 붙는 ref 콜백에서 초점을 옮긴다. 화면에 그려지는 값이 아니라 다음 커밋까지만
  // 남는 예약이라 state가 아니라 ref이고, 그래서 렌더를 한 번 더 돌리지 않는다.
  // (MenusPage와 같은 처리)
  const focusAfterClose = useRef<"edit" | "link" | null>(null);

  const opener = (kind: "edit" | "link") => (node: HTMLButtonElement | null) => {
    if (node && focusAfterClose.current === kind) {
      focusAfterClose.current = null;
      node.focus();
    }
  };

  const closeForm = () => {
    focusAfterClose.current = mode;
    setMode(null);
  };

  // 목록(RestaurantSummary)에는 전화·네이버 링크가 없어 상세를 카드별로 조회한다 (react-query가 캐시)
  const detailQuery = useQuery({
    queryKey: ["restaurant", summary.id],
    queryFn: () => fetchRestaurant(summary.id),
  });
  const detail = detailQuery.data;

  const deleteMutation = useMutation({
    mutationFn: () => deleteRestaurant(summary.id),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ["restaurant", summary.id] });
      onChanged();
      onDeleted();
    },
  });

  if (mode === "edit" && detail) {
    return (
      <li className="card">
        <RestaurantEditForm
          detail={detail}
          onClose={closeForm}
          onSaved={() => {
            closeForm();
            queryClient.invalidateQueries({ queryKey: ["restaurant", summary.id] });
            onChanged();
          }}
        />
      </li>
    );
  }

  return (
    <li className="card">
      <div className="card-main">
        <strong>{summary.name}</strong>
        {detail?.naverUrl && (
          // 새 창으로 열린다는 사실도 이름에 넣는다 — 링크를 따라간 뒤 뒤로 가기가 없어
          // 원래 자리로 못 돌아오는 상황을 미리 알린다.
          <a
            href={detail.naverUrl}
            target="_blank"
            rel="noreferrer"
            aria-label={`${summary.name} 지도 보기 (새 창)`}
          >
            지도 보기
          </a>
        )}
      </div>
      <span>{summary.address || "주소 정보 없음"}</span>
      {detail?.phone && <span>{detail.phone}</span>}
      {deleteMutation.isError && <p className="error" role="alert">{errorMessage(deleteMutation.error)}</p>}
      {/* 버튼 이름에 식당 이름을 넣는다. 없으면 NVDA 요소 목록에서 "수정, 메뉴 연결, 삭제"만
          반복되어 어느 카드의 것인지 알 수 없다. "수정"과 "메뉴 연결"은 확인 단계도 없다. */}
      <div className="card-actions">
        <button
          ref={opener("edit")}
          disabled={!detail}
          aria-label={`${summary.name} 수정`}
          onClick={() => setMode("edit")}
        >
          수정
        </button>
        <button
          ref={opener("link")}
          aria-label={`${summary.name} ${mode === "link" ? "메뉴 연결 닫기" : "메뉴 연결"}`}
          onClick={() => (mode === "link" ? closeForm() : setMode("link"))}
        >
          {mode === "link" ? "메뉴 연결 닫기" : "메뉴 연결"}
        </button>
        <button
          disabled={deleteMutation.isPending}
          aria-label={`${summary.name} 삭제`}
          onClick={() => {
            if (window.confirm(`'${summary.name}' 식당을 삭제할까요?`)) {
              deleteMutation.mutate();
            }
          }}
        >
          삭제
        </button>
      </div>
      {mode === "link" && (
        <MenuLinkForm restaurantId={summary.id} onClose={closeForm} />
      )}
    </li>
  );
}

// ---- 식당 수정 폼 ----

function RestaurantEditForm({
  detail,
  onClose,
  onSaved,
}: {
  detail: RestaurantDetail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(detail.name);
  const [address, setAddress] = useState(detail.address ?? "");
  const [phone, setPhone] = useState(detail.phone ?? "");
  const headingRef = useFocusOnMount<HTMLHeadingElement>();

  // 이름이 비어 제출을 막았을 때 초점을 돌려보낼 칸과, 그 사유를 버튼·입력에 묶을 id.
  const nameRef = useRef<HTMLInputElement>(null);
  const nameErrorId = useId();
  // 누르기 전에는 오류를 띄우지 않는다 — 이름을 지우는 도중부터 빨간 문구가 따라다니면
  // 아직 하지도 않은 실수를 지적하는 꼴이 된다. (MenusPage의 메뉴 폼과 같은 처리)
  const [submitAttempted, setSubmitAttempted] = useState(false);

  const saveMutation = useMutation({
    mutationFn: () =>
      updateRestaurant(detail.id, {
        name: name.trim(),
        address: address.trim() || null,
        phone: phone.trim() || null,
        // 좌표·링크는 수정 UI가 없으므로 기존 값을 유지한다 (Update DTO에서 위경도 필수)
        latitude: detail.latitude,
        longitude: detail.longitude,
        naverUrl: detail.naverUrl,
      }),
    onSuccess: onSaved,
  });

  // "이름이 비었다"와 "요청이 나가 있다"는 성격이 다르다. 앞은 사용자가 무엇을 더 해야
  // 하는 조건이라 사유가 버튼까지 닿아야 하고, 뒤는 기다리면 풀리는 진행 상태다.
  const nameMissing = !name.trim();
  const submitBlocked = saveMutation.isPending || nameMissing;
  // 이름을 다시 채우면 사유가 없어진다 — 한 번 눌렀다는 이유로 해결된 오류가 남으면 안 된다.
  const showNameError = submitAttempted && nameMissing;

  return (
    <form
      className="menu-form"
      // 브라우저 기본 검증을 끈다. required는 "필수"라는 표시로 남기되, 빈 칸을 알리는 일은
      // 핸들러가 맡는다 — 기본 말풍선은 다음 입력에 사라져 화면에 남지 않고 낭독 여부도
      // 브라우저마다 달라, 오류가 전달됐는지를 이쪽에서 보장할 수 없다. 무엇보다 이걸 켜 두면
      // "완전히 빈 칸"과 "공백만 친 칸"이 서로 다른 방식으로 지적되어, 사용자에게는 같은
      // 실수인데 화면이 다르게 반응한다. (인증 화면들과 같은 처리)
      noValidate
      // 제출 경로가 버튼 클릭만이 아니다 — 이름·주소·전화 칸에서 Enter를 쳐도 여기로 온다.
      // 버튼에서 disabled를 뗀 이상 막는 자리는 클릭 핸들러가 아니라 여기다.
      onSubmit={(e) => {
        e.preventDefault();
        if (saveMutation.isPending) return;
        if (nameMissing) {
          // 사유를 role="alert"로 알린 뒤 고칠 수 있는 자리로 초점을 옮긴다 — 초점이 버튼에
          // 남으면 그 칸까지 가는 길은 사용자가 직접 찾아야 한다.
          setSubmitAttempted(true);
          nameRef.current?.focus();
          return;
        }
        saveMutation.mutate();
      }}
    >
      <h2 ref={headingRef} tabIndex={-1}>식당 수정</h2>
      <label>
        식당 이름
        <input
          ref={nameRef}
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={200}
          required
          aria-invalid={showNameError || undefined}
          aria-describedby={showNameError ? nameErrorId : undefined}
        />
      </label>
      {showNameError && (
        <p className="error" role="alert" id={nameErrorId}>식당 이름을 입력해주세요.</p>
      )}
      <label>
        주소
        <input value={address} onChange={(e) => setAddress(e.target.value)} maxLength={300} />
      </label>
      <label>
        전화
        {/* DB가 VARCHAR(20)이라 여기서 막지 않으면 어느 필드가 문제인지 알 수 없는 409가 난다 */}
        <input value={phone} onChange={(e) => setPhone(e.target.value)} maxLength={20} />
      </label>

      {saveMutation.isError && <p className="error" role="alert">{errorMessage(saveMutation.error)}</p>}

      <div className="card-actions">
        {/* disabled를 쓰지 않는 이유가 두 조건에서 서로 다르다.
            - 이름 미입력: disabled면 버튼이 Tab 순회에서 빠져, 키보드·스크린리더 사용자는
              저장 버튼이 있다는 것도 왜 눌리지 않는지도 알 수 없다.
            - 저장 중: 누르는 순간 초점이 <body>로 떨어지고 요청이 끝나도 돌아오지 않는다.
            aria-*는 표시일 뿐 클릭을 막지 않는다 — 막는 일은 위 onSubmit이 한다. */}
        <button
          type="submit"
          aria-busy={saveMutation.isPending}
          aria-disabled={submitBlocked || undefined}
          // 사유 <p>는 눌러 본 뒤에만 그려진다 — 없는 id를 가리키면 참조가 끊긴다.
          aria-describedby={showNameError ? nameErrorId : undefined}
        >
          {saveMutation.isPending ? "저장 중…" : "저장"}
        </button>
        <button type="button" onClick={onClose}>취소</button>
      </div>
    </form>
  );
}

// ---- 메뉴 연결 폼 ----

function MenuLinkForm({
  restaurantId,
  onClose,
}: {
  restaurantId: number;
  onClose: () => void;
}) {
  const [menuId, setMenuId] = useState<number | null>(null);
  const [rating, setRating] = useState(3);
  const ratingLabelId = useId();
  const [memo, setMemo] = useState("");
  const headingRef = useFocusOnMount<HTMLHeadingElement>();

  // 첫 페이지 20개면 충분 — MenusPage의 무한 스크롤 키(["menus"])와 겹치지 않게 별도 키 사용
  const menusQuery = useQuery({
    queryKey: ["menus", "picker"],
    queryFn: () => fetchMenus(undefined, 20),
  });
  const menus = menusQuery.data?.menus ?? [];

  const linkMutation = useMutation({
    mutationFn: () => createMenuRestaurant(menuId!, { restaurantId, rating, memo: memo.trim() || null }),
    onSuccess: onClose,
  });

  // menuId == null 하나가 성격이 다른 두 상황을 덮고 있다. 목록을 받아 봐야 갈리므로
  // 판단 기준은 "조회가 성공했는데 메뉴가 0개인가"다(아직 안 온 것과 정말 없는 것은 다르다).
  // - 등록된 메뉴가 아예 없다: 이 폼에서 사용자가 할 수 있는 일이 없다. 다른 화면에서
  //   메뉴를 먼저 만들어야 하므로 안내는 누르기 전부터 상시로 떠 있어야 하고, 옮길 초점도
  //   없다. 이미 그리고 있던 "등록된 메뉴가 없습니다" 안내를 버튼에 묶기만 하면 된다.
  // - 메뉴는 있는데 아직 고르지 않았다: 지금 여기서 풀 수 있는 조건이라, 눌렀을 때 이유를
  //   알리고 고르는 자리(첫 칩)로 초점을 옮긴다.
  const noMenus = menusQuery.isSuccess && menus.length === 0;
  const selectionMissing = menuId == null;
  const submitBlocked = linkMutation.isPending || selectionMissing;

  const noMenusNoteId = useId();
  const pickErrorId = useId();
  const firstMenuChip = useRef<HTMLButtonElement>(null);
  // 눌러 보기 전에는 오류를 띄우지 않는다 — 폼을 열자마자 아무것도 고르지 않은 것은
  // 실수가 아니다. 고를 칩이 있을 때만 성립하는 안내이기도 하다("고르라"고 해도 목록이
  // 비어 있으면 할 수 있는 일이 없다).
  const [pickAttempted, setPickAttempted] = useState(false);
  const showPickError = pickAttempted && selectionMissing && menus.length > 0;

  // 상시 안내(메뉴 없음)와 눌러야 뜨는 오류(선택 안 함)는 동시에 성립하지 않는다.
  const blockedReasonId = noMenus ? noMenusNoteId : showPickError ? pickErrorId : undefined;

  return (
    <form
      className="menu-form"
      // 이 폼에는 텍스트 입력이 없어 Enter로 새어 나갈 경로가 지금은 없지만, 막는 자리는
      // 그래도 여기다 — 나중에 입력 한 칸만 늘어도 클릭 핸들러의 방어는 그대로 뚫린다.
      onSubmit={(e) => {
        e.preventDefault();
        if (linkMutation.isPending) return;
        if (selectionMissing) {
          setPickAttempted(true);
          // 등록된 메뉴가 없으면 옮겨 갈 칩 자체가 없다 — 초점은 버튼에 두고, 버튼에
          // 묶어 둔 상시 안내가 사유를 대신한다.
          firstMenuChip.current?.focus();
          return;
        }
        linkMutation.mutate();
      }}
    >
      <h2 ref={headingRef} tabIndex={-1}>메뉴 연결</h2>

      <fieldset>
        <legend>메뉴 선택</legend>
        {menusQuery.isPending && <p>불러오는 중…</p>}
        {menusQuery.isError && <p className="error" role="alert">{errorMessage(menusQuery.error)}</p>}
        {noMenus && (
          <p id={noMenusNoteId}>등록된 메뉴가 없습니다. 먼저 메뉴를 등록해 주세요.</p>
        )}
        <div className="chip-row">
          {menus.map((menu, index) => (
            <button
              key={menu.id}
              type="button"
              // 고르지 않은 채 눌렀을 때 초점을 보낼 곳. 칩은 골라도 언마운트되지 않아
              // 첫 칩 하나만 잡아 두면 된다.
              ref={index === 0 ? firstMenuChip : undefined}
              {...chipToggle(menu.id === menuId)}
              onClick={() => setMenuId(menu.id === menuId ? null : menu.id)}
            >
              {menu.name}
            </button>
          ))}
        </div>
        {showPickError && (
          <p className="error" role="alert" id={pickErrorId}>연결할 메뉴를 먼저 선택해주세요.</p>
        )}
      </fieldset>

      {/* <label>로 감싸지 않는 이유는 MenusPage의 선호도 위젯과 같다 —
          <button>이 labelable이라 label 전체가 별 1의 클릭 영역이 된다. */}
      <div className="field">
        <span id={ratingLabelId}>별점</span>
        <span className="weight-picker" role="group" aria-labelledby={ratingLabelId}>
          {[1, 2, 3, 4, 5].map((value) => (
            <button
              key={value}
              type="button"
              {...starToggle(value <= rating)}
              aria-label={`별점 ${value}`}
              onClick={() => setRating(value)}
            >
              {value <= rating ? "★" : "☆"}
            </button>
          ))}
          <small aria-live="polite">{rating}점</small>
        </span>
      </div>

      <label>
        메모
        <textarea
          value={memo}
          onChange={(e) => setMemo(e.target.value)}
          rows={2}
          // 백엔드 MenuRestaurantRequest.memo와 같은 상한
          maxLength={1000}
          placeholder="예: 이 집 김치찌개가 최고"
        />
      </label>

      {linkMutation.isError && <p className="error" role="alert">{errorMessage(linkMutation.error)}</p>}

      <div className="card-actions">
        {/* 저장 버튼과 같은 이유로 disabled를 쓰지 않는다 — 특히 여기서는 "고를 메뉴가
            하나도 없다"가 사용자가 이 화면에서 풀 수 없는 조건이라, 버튼이 Tab 순회에서
            빠지면 왜 막혔는지 알 길이 영영 없어진다. */}
        <button
          type="submit"
          aria-busy={linkMutation.isPending}
          aria-disabled={submitBlocked || undefined}
          aria-describedby={blockedReasonId}
        >
          {linkMutation.isPending ? "연결 중…" : "연결"}
        </button>
        <button type="button" onClick={onClose}>취소</button>
      </div>
    </form>
  );
}
