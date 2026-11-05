"""Render the two documented flowcharts without building the Android application.

Run: python docs/flowcharts/render_flowcharts.py
Requires Pillow and ReportLab. Source diagrams are the neighboring JSON files.
"""

from pathlib import Path
from html import escape
from io import BytesIO
import json
import math
import time
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A3, landscape

ROOT = Path(__file__).resolve().parent
FONT = Path('C:/Windows/Fonts/arial.ttf')
BOLD = Path('C:/Windows/Fonts/arialbd.ttf')
INK = '#193A4B'
LINE = '#456371'
PALETTE = {
    'process': ('#F1F8FA', '#36819A'),
    'decision': ('#FFF6DE', '#AA8022'),
    'terminal': ('#193A4B', '#193A4B'),
    'connector': ('#FFFFFF', '#36819A'),
    'warning': ('#FFF0E9', '#BD6B40'),
    'data': ('#EDF4FD', '#51799E'),
}


def render(spec):
    w, h = spec['size']
    stem = spec['file']
    raster = Image.new('RGB', (w, h), 'white')
    draw = ImageDraw.Draw(raster)
    pdfmetrics.registerFont(TTFont('Flow', str(FONT)))
    pdfmetrics.registerFont(TTFont('FlowBold', str(BOLD)))
    page_w, page_h = landscape(A3) if w >= h else A3
    pdf = canvas.Canvas(str(ROOT / (stem + '.pdf')), pagesize=(page_w, page_h))
    pdf.setTitle(spec['title'])
    pdf.setAuthor('HydroSense project')
    scale = min((page_w - 36) / w, (page_h - 36) / h)
    pdf.translate((page_w - w * scale) / 2, (page_h - h * scale) / 2)
    pdf.scale(scale, scale)
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">',
           f'<title>{escape(spec["title"])}</title>',
           f'<desc>{escape(spec["subtitle"])}</desc>',
           '<rect width="100%" height="100%" fill="white"/>']

    def line(points, color=LINE, width=3, arrow=False, dashed=False):
        draw.line(points, fill=color, width=width, joint='curve')
        pdf.setStrokeColor(color)
        pdf.setLineWidth(width)
        pdf.setDash([9, 6] if dashed else [])
        p = pdf.beginPath()
        p.moveTo(points[0][0], h - points[0][1])
        for x, y in points[1:]:
            p.lineTo(x, h - y)
        pdf.drawPath(p)
        pdf.setDash([])
        dash = ' stroke-dasharray="9 6"' if dashed else ''
        svg.append(f'<polyline points="{" ".join(f"{x},{y}" for x,y in points)}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linejoin="round"{dash}/>')
        if arrow:
            x, y = points[-1]
            px, py = points[-2]
            angle = math.atan2(y - py, x - px)
            size = 13
            tri = [(x, y), (x - size * math.cos(angle) + 6 * math.sin(angle), y - size * math.sin(angle) - 6 * math.cos(angle)),
                   (x - size * math.cos(angle) - 6 * math.sin(angle), y - size * math.sin(angle) + 6 * math.cos(angle))]
            polygon(tri, color, color, 1)

    def polygon(points, fill, stroke, width=2):
        draw.polygon(points, fill=fill)
        draw.line(points + [points[0]], fill=stroke, width=width, joint='curve')
        pdf.setFillColor(fill)
        pdf.setStrokeColor(stroke)
        pdf.setLineWidth(width)
        p = pdf.beginPath()
        p.moveTo(points[0][0], h - points[0][1])
        for x, y in points[1:]:
            p.lineTo(x, h - y)
        p.close()
        pdf.drawPath(p, fill=1, stroke=1)
        svg.append(f'<polygon points="{" ".join(f"{x},{y}" for x,y in points)}" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>')

    def rect(x, y, bw, bh, fill, stroke, radius=8):
        box = (x, y, x + bw, y + bh)
        draw.rounded_rectangle(box, radius=radius, fill=fill, outline=stroke, width=2)
        pdf.setFillColor(fill)
        pdf.setStrokeColor(stroke)
        pdf.setLineWidth(2)
        pdf.roundRect(x, h - y - bh, bw, bh, radius, fill=1, stroke=1)
        svg.append(f'<rect x="{x}" y="{y}" width="{bw}" height="{bh}" rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')

    def text(x, y, value, size=25, bold=False, color=INK, anchor='middle', background=False):
        font = ImageFont.truetype(str(BOLD if bold else FONT), size)
        font_name = 'FlowBold' if bold else 'Flow'
        rows = value.split('\n')
        spacing = size * 1.24
        top = y - (len(rows) - 1) * spacing / 2
        for i, row in enumerate(rows):
            cy = top + i * spacing
            tw = pdfmetrics.stringWidth(row, font_name, size)
            left = x - tw / 2 if anchor == 'middle' else x
            if background:
                rect(left - 6, cy - size * 0.65, tw + 12, size * 1.3, '#FFFFFF', '#FFFFFF', 0)
            draw.text((x, cy), row, font=font, fill=color, anchor='mm' if anchor == 'middle' else 'lm')
            pdf.setFont(font_name, size)
            pdf.setFillColor(color)
            pdf.drawString(left, h - cy - size * 0.34, row)
            svg.append(f'<text x="{x}" y="{cy}" fill="{color}" font-family="Arial, sans-serif" font-size="{size}" font-weight="{700 if bold else 400}" text-anchor="{anchor}" dominant-baseline="central">{escape(row)}</text>')

    text(70, 65, spec['title'], 46, True, anchor='start')
    text(70, 120, spec['subtitle'], 24, anchor='start')
    line([(70, 155), (w - 70, 155)], '#8CB6C3', 2)

    for edge in spec['edges']:
        line([tuple(p) for p in edge['points']], arrow=True, dashed=edge.get('dashed', False))

    nodes = {n['id']: n for n in spec['nodes']}
    for node in spec['nodes']:
        x, y, bw, bh = node['box']
        kind = node.get('kind', 'process')
        fill, stroke = PALETTE[kind]
        if kind == 'decision':
            polygon([(x + bw / 2, y), (x + bw, y + bh / 2), (x + bw / 2, y + bh), (x, y + bh / 2)], fill, stroke)
        elif kind == 'data':
            polygon([(x + 20, y), (x + bw, y), (x + bw - 20, y + bh), (x, y + bh)], fill, stroke)
        else:
            rect(x, y, bw, bh, fill, stroke, bh / 2 if kind in ['terminal', 'connector'] else 9)
        text(x + bw / 2, y + bh / 2, node['text'], node.get('font', 25), kind in ['terminal', 'connector'], '#FFFFFF' if kind == 'terminal' else INK)

    for edge in spec['edges']:
        if edge.get('label'):
            text(*edge['label_at'], edge['label'], edge.get('label_font', 22), background=True)
    for note in spec.get('notes', []):
        text(*note['at'], note['text'], note.get('font', 22), note.get('bold', False), anchor=note.get('anchor', 'start'))
    line([(70, h - 175), (w - 70, h - 175)], '#8CB6C3', 2)
    for i, foot in enumerate(spec.get('footer', [])):
        text(70, h - 139 + i * 34, foot, 22, anchor='start')

    svg.append('</svg>')
    (ROOT / (stem + '.svg')).write_text('\n'.join(svg), encoding='utf-8')
    def save_png(im, path, **kwargs):
        data = BytesIO()
        im.save(data, format='PNG', **kwargs)
        # Windows thumbnail readers can briefly lock a previously viewed image.
        for attempt in range(10):
            try:
                path.write_bytes(data.getvalue())
                break
            except OSError:
                if attempt == 9:
                    raise
                time.sleep(0.25)

    save_png(raster, ROOT / (stem + '.png'), dpi=(200, 200))
    pdf.showPage()
    pdf.save()
    preview = raster.copy()
    preview.thumbnail((1250, 1250))
    save_png(preview, ROOT / (stem + '-preview.png'))

    # A native diagrams.net document preserves editable shapes, text, and connectors.
    mxfile = ET.Element('mxfile', host='app.diagrams.net')
    diagram = ET.SubElement(mxfile, 'diagram', name=spec['title'], id=stem)
    model = ET.SubElement(diagram, 'mxGraphModel', page='1', pageWidth=str(w), pageHeight=str(h), grid='1', gridSize='10')
    root = ET.SubElement(model, 'root')
    ET.SubElement(root, 'mxCell', id='0')
    ET.SubElement(root, 'mxCell', id='1', parent='0')

    def mx_text(id_, x, y, value, size, bw=1900, bh=65, bold=False):
        cell = ET.SubElement(root, 'mxCell', id=id_, value=value.replace('\n', '<br>'), style=f'text;html=1;align=left;verticalAlign=middle;fontFamily=Arial;fontColor={INK};fontSize={size};fontStyle={1 if bold else 0};', vertex='1', parent='1')
        ET.SubElement(cell, 'mxGeometry', x=str(x), y=str(y), width=str(bw), height=str(bh), **{'as': 'geometry'})
    mx_text('title', 70, 30, spec['title'], 46, bold=True)
    mx_text('subtitle', 70, 90, spec['subtitle'], 24)
    for n in spec['nodes']:
        kind = n.get('kind', 'process')
        fill, stroke = PALETTE[kind]
        shape = {'decision': 'rhombus;', 'terminal': 'rounded=1;arcSize=100;', 'connector': 'ellipse;', 'data': 'shape=parallelogram;perimeter=parallelogramPerimeter;'}.get(kind, 'rounded=1;arcSize=10;')
        style = shape + f'whiteSpace=wrap;html=1;fontFamily=Arial;fontSize={n.get("font",25)};fillColor={fill};strokeColor={stroke};strokeWidth=2;fontColor={"#FFFFFF" if kind == "terminal" else INK};'
        cell = ET.SubElement(root, 'mxCell', id=n['id'], value=n['text'].replace('\n', '<br>'), style=style, vertex='1', parent='1')
        x, y, bw, bh = n['box']
        ET.SubElement(cell, 'mxGeometry', x=str(x), y=str(y), width=str(bw), height=str(bh), **{'as': 'geometry'})
    for i, e in enumerate(spec['edges']):
        style = f'endArrow=block;endFill=1;strokeColor={LINE};strokeWidth=2;rounded=0;fontFamily=Arial;fontSize=22;html=1;labelBackgroundColor=#FFFFFF;'
        cell = ET.SubElement(root, 'mxCell', id=f'edge{i}', value='', style=style, edge='1', parent='1')
        geo = ET.SubElement(cell, 'mxGeometry', relative='1', **{'as': 'geometry'})
        pts = e['points']
        for tag, pt in [('sourcePoint', pts[0]), ('targetPoint', pts[-1])]:
            ET.SubElement(geo, 'mxPoint', x=str(pt[0]), y=str(pt[1]), **{'as': tag})
        if len(pts) > 2:
            arr = ET.SubElement(geo, 'Array', **{'as': 'points'})
            for px, py in pts[1:-1]:
                ET.SubElement(arr, 'mxPoint', x=str(px), y=str(py))
        if e.get('label'):
            lx, ly = e['label_at']
            mx_text(f'label{i}', lx - 115, ly - 18, e['label'], 22, 230, 40)
    for i, note in enumerate(spec.get('notes', [])):
        mx_text(f'note{i}', note['at'][0], note['at'][1] - 20, note['text'], note.get('font', 22), bh=100)
    for i, foot in enumerate(spec.get('footer', [])):
        mx_text(f'foot{i}', 70, h - 163 + i * 34, foot, 22)
    ET.ElementTree(mxfile).write(ROOT / (stem + '.drawio'), encoding='utf-8', xml_declaration=True)
    print(f'{stem}: PDF, PNG, SVG and editable DRAWIO saved ({len(nodes)} nodes).')


if __name__ == '__main__':
    for path in sorted(ROOT.glob('*-flowchart.json')):
        render(json.loads(path.read_text(encoding='utf-8')))
