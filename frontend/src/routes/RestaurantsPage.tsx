import { useState } from "react";
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

export default function RestaurantsPage() {
  const queryClient = useQueryClient();

  const restaurantsQuery = useQuery({
    queryKey: ["restaurants"],
    queryFn: fetchRestaurants,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["restaurants"] });

  const restaurants = restaurantsQuery.data ?? [];

  return (
    <div className="page">
      <header className="page-header">
        <h2>내 식당</h2>
      </header>

      <PlaceSearch onSaved={invalidate} />

      <h3>저장한 식당</h3>
      {restaurantsQuery.isPending && <p>불러오는 중…</p>}
      {restaurantsQuery.isError && <p className="error">{errorMessage(restaurantsQuery.error)}</p>}
      {restaurantsQuery.isSuccess && restaurants.length === 0 && (
        <p>저장한 식당이 없습니다. 위에서 장소를 검색해 자주 가는 식당을 저장해 보세요.</p>
      )}

      <ul className="card-list">
        {restaurants.map((restaurant) => (
          <RestaurantCard key={restaurant.id} summary={restaurant} onChanged={invalidate} />
        ))}
      </ul>
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
      }),
    onSuccess: onSaved,
  });

  const places = searchQuery.data?.documents ?? [];

  return (
    <section className="menu-form">
      <h3>장소 검색으로 식당 추가</h3>
      <form
        className="inline-add"
        onSubmit={(e) => {
          e.preventDefault();
          setSubmitted(keyword.trim());
        }}
      >
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          maxLength={100}
          placeholder="상호명이나 지역+메뉴로 검색 (예: 역삼동 김치찌개)"
        />
        <button type="submit" disabled={!keyword.trim() || searchQuery.isFetching}>
          {searchQuery.isFetching ? "검색 중…" : "검색"}
        </button>
      </form>

      {searchQuery.isError && <p className="error">{errorMessage(searchQuery.error)}</p>}
      {saveMutation.isError && <p className="error">{errorMessage(saveMutation.error)}</p>}
      {searchQuery.isSuccess && places.length === 0 && (
        <p>'{submitted}' 검색 결과가 없습니다. 다른 키워드로 검색해 보세요.</p>
      )}

      {places.length > 0 && (
        <ul className="card-list">
          {places.map((place, index) => (
            <li key={`${place.place_name}-${place.x}-${place.y}-${index}`} className="card">
              <div className="card-main">
                <strong>{place.place_name}</strong>
                {place.category_name && <span className="chip">{place.category_name}</span>}
              </div>
              <span>{place.road_address_name || place.address_name || "주소 정보 없음"}</span>
              <div className="card-actions">
                <button
                  disabled={saveMutation.isPending}
                  onClick={() => saveMutation.mutate(place)}
                >
                  {saveMutation.isPending && saveMutation.variables === place
                    ? "저장 중…"
                    : "저장"}
                </button>
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
}: {
  summary: RestaurantSummary;
  onChanged: () => void;
}) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<"edit" | "link" | null>(null);

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
    },
  });

  if (mode === "edit" && detail) {
    return (
      <li className="card">
        <RestaurantEditForm
          detail={detail}
          onClose={() => setMode(null)}
          onSaved={() => {
            setMode(null);
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
          <a href={detail.naverUrl} target="_blank" rel="noreferrer">
            지도 보기
          </a>
        )}
      </div>
      <span>{summary.address || "주소 정보 없음"}</span>
      {detail?.phone && <span>{detail.phone}</span>}
      {deleteMutation.isError && <p className="error">{errorMessage(deleteMutation.error)}</p>}
      <div className="card-actions">
        <button disabled={!detail} onClick={() => setMode("edit")}>수정</button>
        <button onClick={() => setMode(mode === "link" ? null : "link")}>
          {mode === "link" ? "메뉴 연결 닫기" : "메뉴 연결"}
        </button>
        <button
          disabled={deleteMutation.isPending}
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
        <MenuLinkForm restaurantId={summary.id} onClose={() => setMode(null)} />
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
        naverPlaceId: detail.naverPlaceId,
      }),
    onSuccess: onSaved,
  });

  return (
    <form
      className="menu-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (name.trim()) saveMutation.mutate();
      }}
    >
      <h3>식당 수정</h3>
      <label>
        식당 이름
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={200}
          required
        />
      </label>
      <label>
        주소
        <input value={address} onChange={(e) => setAddress(e.target.value)} maxLength={300} />
      </label>
      <label>
        전화
        <input value={phone} onChange={(e) => setPhone(e.target.value)} maxLength={30} />
      </label>

      {saveMutation.isError && <p className="error">{errorMessage(saveMutation.error)}</p>}

      <div className="card-actions">
        <button type="submit" disabled={saveMutation.isPending || !name.trim()}>
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
  const [memo, setMemo] = useState("");

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

  return (
    <form
      className="menu-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (menuId != null) linkMutation.mutate();
      }}
    >
      <h3>메뉴 연결</h3>

      <fieldset>
        <legend>메뉴 선택</legend>
        {menusQuery.isPending && <p>불러오는 중…</p>}
        {menusQuery.isError && <p className="error">{errorMessage(menusQuery.error)}</p>}
        {menusQuery.isSuccess && menus.length === 0 && (
          <p>등록된 메뉴가 없습니다. 먼저 메뉴를 등록해 주세요.</p>
        )}
        <div className="chip-row">
          {menus.map((menu) => (
            <button
              key={menu.id}
              type="button"
              className={menu.id === menuId ? "chip selectable on" : "chip selectable"}
              onClick={() => setMenuId(menu.id === menuId ? null : menu.id)}
            >
              {menu.name}
            </button>
          ))}
        </div>
      </fieldset>

      <label>
        별점
        <span className="weight-picker">
          {[1, 2, 3, 4, 5].map((value) => (
            <button
              key={value}
              type="button"
              className={value <= rating ? "star on" : "star"}
              aria-label={`별점 ${value}`}
              onClick={() => setRating(value)}
            >
              ★
            </button>
          ))}
          <small>{rating}점</small>
        </span>
      </label>

      <label>
        메모
        <textarea
          value={memo}
          onChange={(e) => setMemo(e.target.value)}
          rows={2}
          placeholder="예: 이 집 김치찌개가 최고"
        />
      </label>

      {linkMutation.isError && <p className="error">{errorMessage(linkMutation.error)}</p>}

      <div className="card-actions">
        <button type="submit" disabled={linkMutation.isPending || menuId == null}>
          {linkMutation.isPending ? "연결 중…" : "연결"}
        </button>
        <button type="button" onClick={onClose}>취소</button>
      </div>
    </form>
  );
}
