// ═══════════════════════════════════════════════════════════════════
//  تعديل خاص بنظام LorkERP — تشغيل Evolution API داخل عملية النظام
//
//  main.ts الأصلي ينشئ تطبيق Express ويستمع على منفذ خاص به.
//  هذا الملف يعيد بناء نفس الحزمة (نفس الموجّهات، نفس التهيئة) لكن
//  دون listen()، ويُعيد التطبيق ليُركَّب داخل خادم LorkERP على نفس
//  المنفذ وداخل نفس العملية.
//
//  التطبيق المُعاد هو sub-application كامل: إعداداته (محرك القوالب hbs،
//  الملفات الساكنة، حدود الجسم) محصورة فيه ولا تتسرب إلى التطبيق الأب،
//  فلا يتأثر محرك EJS ولا أي مسار قائم في النظام.
//
//  main.ts يبقى كما هو ويعمل كالمعتاد للتشغيل المستقل.
// ═══════════════════════════════════════════════════════════════════

import '@utils/instrumentSentry';

import { ProviderFiles } from '@api/provider/sessions';
import { PrismaRepository } from '@api/repository/repository.service';
import { HttpStatus, router } from '@api/routes/index.router';
import { eventManager, waMonitor } from '@api/server.module';
import { Auth, configService, Cors, HttpServer, ProviderSession, Webhook } from '@config/env.config';
import { Logger } from '@config/logger.config';
import { ROOT_DIR } from '@config/path.config';
import axios from 'axios';
import compression from 'compression';
import cors from 'cors';
import express, { Express, json, NextFunction, Request, Response, urlencoded } from 'express';
import { Server as HttpServerInstance } from 'http';
import { join } from 'path';

export interface EmbeddedOptions {
  /**
   * خادم HTTP الخاص بالتطبيق المضيف. يحتاجه eventManager لتركيب
   * Socket.io على نفس الخادم بدل إنشاء خادم ثانٍ.
   */
  httpServer: HttpServerInstance;

  /** تحميل نسخ واتساب المحفوظة عند الإقلاع. الافتراضي: نعم */
  loadInstances?: boolean;
}

let cachedApp: Express | null = null;

/**
 * إنشاء تطبيق Evolution API جاهز للتركيب.
 * يُستدعى مرة واحدة؛ الاستدعاءات اللاحقة تُعيد نفس التطبيق.
 */
export async function createEvolutionApp(options: EmbeddedOptions): Promise<Express> {
  if (cachedApp) return cachedApp;

  const logger = new Logger('EMBEDDED');
  const app = express();

  let providerFiles: ProviderFiles = null;
  if (configService.get<ProviderSession>('PROVIDER').ENABLED) {
    providerFiles = new ProviderFiles(configService);
    await providerFiles.onModuleInit();
    logger.info('Provider:Files - ON');
  }

  const prismaRepository = new PrismaRepository(configService);
  await prismaRepository.onModuleInit();

  app.use(
    cors({
      origin(requestOrigin, callback) {
        const { ORIGIN } = configService.get<Cors>('CORS');
        if (ORIGIN.includes('*')) {
          return callback(null, true);
        }
        if (ORIGIN.indexOf(requestOrigin) !== -1) {
          return callback(null, true);
        }
        return callback(new Error('Not allowed by CORS'));
      },
      methods: [...configService.get<Cors>('CORS').METHODS],
      credentials: configService.get<Cors>('CORS').CREDENTIALS,
    }),
    urlencoded({ extended: true, limit: '136mb' }),
    json({ limit: '136mb' }),
    compression(),
  );

  // هذه الإعدادات محصورة في هذا التطبيق الفرعي ولا تمس التطبيق الأب
  app.set('view engine', 'hbs');
  app.set('views', join(ROOT_DIR, 'views'));
  app.use(express.static(join(ROOT_DIR, 'public')));
  app.use('/store', express.static(join(ROOT_DIR, 'store')));

  app.use('/', router);

  app.use(
    (err: Error, req: Request, res: Response, next: NextFunction) => {
      if (err) {
        const webhook = configService.get<Webhook>('WEBHOOK');

        if (webhook.EVENTS.ERRORS_WEBHOOK && webhook.EVENTS.ERRORS_WEBHOOK != '' && webhook.EVENTS.ERRORS) {
          const tzoffset = new Date().getTimezoneOffset() * 60000;
          const localISOTime = new Date(Date.now() - tzoffset).toISOString();
          const now = localISOTime;
          const globalApiKey = configService.get<Auth>('AUTHENTICATION').API_KEY.KEY;
          const serverUrl = configService.get<HttpServer>('SERVER').URL;

          const errorData = {
            event: 'error',
            data: {
              error: err['error'] || 'Internal Server Error',
              message: err['message'] || 'Internal Server Error',
              status: err['status'] || 500,
              response: {
                message: err['message'] || 'Internal Server Error',
              },
            },
            date_time: now,
            api_key: globalApiKey,
            server_url: serverUrl,
          };

          logger.error(errorData);

          const baseURL = webhook.EVENTS.ERRORS_WEBHOOK;
          const httpService = axios.create({ baseURL });

          httpService.post('', errorData);
        }

        return res.status(err['status'] || 500).json({
          status: err['status'] || 500,
          error: err['error'] || 'Internal Server Error',
          response: {
            message: err['message'] || 'Internal Server Error',
          },
        });
      }

      next();
    },
    (req: Request, res: Response, next: NextFunction) => {
      const { method, url } = req;

      res.status(HttpStatus.NOT_FOUND).json({
        status: HttpStatus.NOT_FOUND,
        error: 'Not Found',
        response: {
          message: [`Cannot ${method.toUpperCase()} ${url}`],
        },
      });

      next();
    },
  );

  // Socket.io يُركَّب على خادم المضيف بدل إنشاء خادم منفصل
  eventManager.init(options.httpServer);

  if (options.loadInstances !== false) {
    waMonitor.loadInstance().catch((error) => {
      logger.error('Error loading instances: ' + error);
    });
  }

  cachedApp = app;
  logger.info('Evolution API mounted inside LorkERP process');

  return app;
}

/** تحرير المرجع المخزّن — يُستدعى عند إيقاف النظام */
export function resetEvolutionApp(): void {
  cachedApp = null;
}
