"""Generate deterministic text/vector PDF fixtures (no third-party Python packages)."""
from pathlib import Path
import sys

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
for pages in (150, 500, 1000):
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    kids = []
    for page in range(pages):
        page_id = len(objects) + 1
        kids.append(f'{page_id} 0 R')
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {page_id + 1} 0 R >>'.encode())
        content = f'BT /F1 10 Tf 40 740 Td (Jahfan benchmark page {page+1}) Tj 14 TL\n'.encode()
        content += b'T* (Text selection and rendering benchmark 0123456789 abcdefghijklmnopqrstuvwxyz) Tj\n' * 45
        content += b'ET\n0.3 w\n' + b'40 50 m 560 50 l S\n' * 30
        objects.append(b'<< /Length ' + str(len(content)).encode() + b' >>\nstream\n' + content + b'\nendstream')
    objects[1] = f'<< /Type /Pages /Count {pages} /Kids [{" ".join(kids)}] >>'.encode()
    data = bytearray(b'%PDF-1.7\n')
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data)); data += f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(data)
    data += f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{n:010} 00000 n \n'.encode() for n in offsets[1:])
    data += f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
    (out / f'jahfan-{pages}.pdf').write_bytes(data)
    print(pages, len(data))
