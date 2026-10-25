import { Route, Routes } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout'
import PlaceholderPage from '../pages/PlaceholderPage'
import OpportunitiesPage from '../pages/opportunities/OpportunitiesPage'
import OpportunityDetailPage from '../pages/opportunities/OpportunityDetailPage'
import HomePage from '../pages/home/HomePage'

function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="opportunities" element={<OpportunitiesPage />} />
        <Route path="opportunities/:id" element={<OpportunityDetailPage />} />
        <Route path="saved" element={<PlaceholderPage title="저장한 기회" description="저장한 기회를 모아 볼 화면을 준비하고 있어요." />} />
        <Route path="onboarding" element={<PlaceholderPage title="초기 프로필 설정" description="나에게 맞는 기회를 찾기 위한 프로필 설정 화면을 준비하고 있어요." />} />
        <Route path="mypage" element={<PlaceholderPage title="마이페이지" description="내 프로필을 확인하고 관리할 화면을 준비하고 있어요." />} />
        <Route path="notifications" element={<PlaceholderPage title="알림 목록" description="기회의 변경 소식을 확인할 알림 화면을 준비하고 있어요." />} />
        <Route path="settings/notifications" element={<PlaceholderPage title="알림 설정" description="알림 수신 방법을 관리할 설정 화면을 준비하고 있어요." />} />
        <Route path="*" element={<PlaceholderPage title="404 · 페이지를 찾을 수 없어요" description="주소를 확인하거나 상단 메뉴에서 원하는 화면으로 이동해 주세요." />} />
      </Route>
    </Routes>
  )
}

export default AppRoutes
