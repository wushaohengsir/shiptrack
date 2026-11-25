import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue')
    },
    {
      path: '/',
      component: () => import('@/components/AppShell.vue'),
      children: [
        { path: '', redirect: '/orders' },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/OrderListView.vue')
        },
        {
          path: 'containers/:id/timeline',
          name: 'container-timeline',
          component: () => import('@/views/ContainerTimelineView.vue'),
          props: (route) => ({
            containerId: Number(route.params.id),
            orderId: route.query.orderId ? Number(route.query.orderId) : null,
            containerNo: typeof route.query.containerNo === 'string' ? route.query.containerNo : ''
          })
        },
        {
          path: 'stats',
          name: 'stats',
          component: () => import('@/views/StatsOverviewView.vue')
        }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/orders' }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.name !== 'login' && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isAuthenticated) {
    return { name: 'orders' }
  }
  return true
})

export default router
