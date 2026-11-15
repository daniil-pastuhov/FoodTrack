/**
 * Cloud Storage side of the always-on board (src/board/hosted.ts): reads the bucket's settings and
 * IAM policy and writes one object. Never creates a bucket or changes a policy. Needs, on that
 * bucket only, storage.buckets.get, storage.buckets.getIamPolicy, storage.objects.create and
 * storage.objects.delete (overwriting); README "The board", "Always on".
 */
import type { Storage } from "firebase-admin/storage";
import type { BoardBucket, BoardObject, BucketFacts } from "../board/hosted";

type Bucket = ReturnType<Storage["bucket"]>;

export class CloudStorageBoardBucket implements BoardBucket {
  constructor(private readonly bucket: Bucket) {}

  get name(): string {
    return this.bucket.name;
  }

  async facts(): Promise<BucketFacts | null> {
    let metadata;
    try {
      [metadata] = await this.bucket.getMetadata();
    } catch (e) {
      if ((e as { code?: unknown }).code === 404) return null;
      throw e;
    }
    const [policy] = await this.bucket.iam.getPolicy({ requestedPolicyVersion: 3 });
    return {
      publicAccessPrevention: metadata.iamConfiguration?.publicAccessPrevention ?? null,
      uniformBucketLevelAccess: metadata.iamConfiguration?.uniformBucketLevelAccess?.enabled === true,
      bindings: (policy.bindings ?? []).map((b) => ({ role: b.role, members: b.members ?? [] })),
    };
  }

  async write(object: string, content: BoardObject): Promise<void> {
    await this.bucket.file(object).save(content.body, {
      resumable: false,
      contentType: content.contentType,
      metadata: { contentType: content.contentType, cacheControl: content.cacheControl, contentDisposition: content.contentDisposition },
    });
  }
}
