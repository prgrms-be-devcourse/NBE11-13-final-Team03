import { Route, Routes } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout'
import PlaceholderPage from '../pages/PlaceholderPage'
import OpportunitiesPage from '../pages/opportunities/OpportunitiesPage'
import OpportunityDetailPage from '../pages/opportunities/OpportunityDetailPage'
import HomePage from '../pages/home/HomePage'
import AuthProvider from '../auth/AuthProvider'
import LoginPage from '../pages/auth/LoginPage'
import LoginResultPage from '../pages/auth/LoginResultPage'
import { MyPage, OnboardingPage, ProfileSettingsPage } from '../pages/profile/ProfilePages'

function AppRoutes() {
  return (
    <AuthProvider><Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="login-success.html" element={<LoginResultPage success />} />
        <Route path="login-failure.html" element={<LoginResultPage success={false} />} />
        <Route path="opportunities" element={<OpportunitiesPage />} />
        <Route path="opportunities/:id" element={<OpportunityDetailPage />} />
        <Route path="saved" element={<PlaceholderPage title="저장한 기회" description="저장한 기회를 모아 볼 화면을 준비하고 있어요." />} />
        <Route path="onboarding" element={<OnboardingPage />} />
        <Route path="mypage" element={<MyPage />} />
        <Route path="mypage/profile" element={<ProfileSettingsPage kind="profile" />} />
        <Route path="mypage/interests" element={<ProfileSettingsPage kind="interests" />} />
        <Route path="notifications" element={<PlaceholderPage title="알림 목록" description="기회의 변경 소식을 확인할 알림 화면을 준비하고 있어요." />} />
        <Route path="settings/notifications" element={<PlaceholderPage title="알림 설정" description="알림 수신 방법을 관리할 설정 화면을 준비하고 있어요." />} />
        <Route path="*" element={<PlaceholderPage title="404 · 페이지를 찾을 수 없어요" description="주소를 확인하거나 상단 메뉴에서 원하는 화면으로 이동해 주세요." />} />
      </Route>
    </Routes></AuthProvider>
  )
}

export default AppRoutes
