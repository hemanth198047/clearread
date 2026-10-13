-- Align default tenant branding colors with Rebeautifier theme (v1.1.2).
UPDATE branding_settings
SET primary_color = '#1e293b',
    secondary_color = '#f59e0b',
    updated_at = NOW()
WHERE tenant_id = '11111111-1111-1111-1111-111111111111'
  AND primary_color IN ('#1a5f4a', '#1A5F4A')
  AND secondary_color IN ('#c4a35a', '#C4A35A');
