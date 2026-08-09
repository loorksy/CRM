import { join, resolve } from 'path';

/**
 * تعديل خاص بنظام LorkERP:
 * عند تشغيل Evolution داخل عملية النظام، تكون process.cwd() هي جذر الـ ERP،
 * فتُنشأ مجلدات store/ و instances/ في المكان الخطأ. متغير EVOLUTION_ROOT_DIR
 * يثبّت الجذر على مجلد Evolution نفسه. السلوك الافتراضي (تشغيل مستقل) لم يتغير.
 */
export const ROOT_DIR = process.env.EVOLUTION_ROOT_DIR ? resolve(process.env.EVOLUTION_ROOT_DIR) : process.cwd();
export const INSTANCE_DIR = join(ROOT_DIR, 'instances');
export const SRC_DIR = join(ROOT_DIR, 'src');
export const AUTH_DIR = join(ROOT_DIR, 'store', 'auth');
export const STORE_DIR = join(ROOT_DIR, 'store');
