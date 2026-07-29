import dayjs from 'dayjs';

export function formatDate(value?: string): string {
  if (!value) return '-';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD') : value;
}

export function formatDateTime(value?: string): string {
  if (!value) return '-';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : value;
}

export function maskPhone(phone?: string): string {
  if (!phone || phone.length < 7) return phone || '';
  return phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2');
}
