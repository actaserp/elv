-- ─────────────────────────────────────────────────────────────
--  본사 PostgreSQL — 사업체별 KT 회선번호
--
--  전화가 오면 어느 사업체 화면에 띄울지 가려내는 데 쓴다.
--  에이전트가 자기 KT 계정(= 회선번호)을 알려주면 서버가 이 표에서 사업체를 찾는다.
--
--  사업체는 내부 코드를 몰라도 되고, 설치 프로그램도 하나면 된다.
--  회선이 여러 개인 사업체가 있어 쉼표로 여러 개를 적는다.
--
--  적용: psql -h <본사> -U <계정> -d <DB> -f 001_tenant_cti_lines.sql
-- ─────────────────────────────────────────────────────────────

ALTER TABLE tb_tenant_db
    ADD COLUMN IF NOT EXISTS cti_lines varchar(200);

COMMENT ON COLUMN tb_tenant_db.cti_lines IS
    'KT 통화매니저 회선번호. 여러 개면 쉼표로 구분. 예: 0269595020,0269595021';

-- 확인
-- SELECT id, spjangcd, db_alias, cti_lines FROM tb_tenant_db ORDER BY id;
