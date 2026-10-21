import { createRouter, createWebHistory } from 'vue-router'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('../pages/HomePage.vue'), meta: { title: 'Home' } },
    { path: '/overview', name: 'overview', component: () => import('../pages/OverviewPage.vue'), meta: { title: 'Overview' } },
    { path: '/instances', name: 'instances', component: () => import('../pages/InstancesPage.vue'), meta: { title: 'Instances' } },
    { path: '/services', name: 'services', component: () => import('../pages/ServicesPage.vue'), meta: { title: 'Services' } },
    { path: '/settings', name: 'settings', component: () => import('../pages/SettingsPage.vue'), meta: { title: 'Settings' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
