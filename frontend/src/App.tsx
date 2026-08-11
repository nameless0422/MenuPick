import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LoginPage from "./routes/LoginPage";
import OAuthCallbackPage from "./routes/OAuthCallbackPage";
import ProtectedRoute from "./routes/ProtectedRoute";
import Layout from "./routes/Layout";
import PickPage from "./routes/PickPage";
import MenusPage from "./routes/MenusPage";
import RestaurantsPage from "./routes/RestaurantsPage";
import HistoryPage from "./routes/HistoryPage";
import SettingsPage from "./routes/SettingsPage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/oauth/kakao/callback" element={<OAuthCallbackPage provider="kakao" />} />
        <Route path="/oauth/google/callback" element={<OAuthCallbackPage provider="google" />} />

        <Route
          element={
            <ProtectedRoute>
              <Layout />
            </ProtectedRoute>
          }
        >
          <Route path="/pick" element={<PickPage />} />
          <Route path="/menus" element={<MenusPage />} />
          <Route path="/restaurants" element={<RestaurantsPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>

        <Route path="*" element={<Navigate to="/pick" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
