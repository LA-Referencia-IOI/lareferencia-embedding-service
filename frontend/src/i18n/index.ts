import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { words } from './translations'
void i18n
  .use(initReactI18next)
  .init({
    resources: { pt: { translation: words.pt }, es: { translation: words.es } },
    lng: localStorage.getItem('language') === 'es' ? 'es' : 'pt',
    fallbackLng: 'pt',
    interpolation: { escapeValue: false },
  })
export default i18n
